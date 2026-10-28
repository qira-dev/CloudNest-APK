package com.qiradev.cloudnest.engine.download

import android.content.Context
import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.entity.FileStatus
import com.qiradev.cloudnest.data.entity.JobKind
import com.qiradev.cloudnest.data.entity.JobStatus
import com.qiradev.cloudnest.data.entity.TransferJobEntity
import com.qiradev.cloudnest.engine.health.HealthTracker
import com.qiradev.cloudnest.engine.providers.ProviderConnector
import com.qiradev.cloudnest.engine.upload.NetworkGate
import com.qiradev.cloudnest.engine.upload.NetworkGate.AlwaysAllowed
import com.qiradev.cloudnest.engine.upload.TokenProvider
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

/** Where the assembled file goes (specs/03 §4). */
interface DownloadOutput {
    fun openStream(): OutputStream

    /** Partial output is always discarded on stop/failure (specs/03 §5). */
    fun discardPartial()
}

/** Cache-dir export (specs/03 §4.1): assembled for immediate open/share. */
class CacheFileOutput(private val file: File) : DownloadOutput {
    override fun openStream(): OutputStream = file.outputStream()
    override fun discardPartial() {
        file.delete()
    }

    companion object {
        fun exportsDir(context: Context): File = File(context.cacheDir, "exports").apply { mkdirs() }

        fun newExport(context: Context, fileName: String): CacheFileOutput =
            CacheFileOutput(File(exportsDir(context), fileName))

        /** Cache cleanup — exports are transient (specs/03 §4.1). */
        fun clear(context: Context): Int {
            val dir = exportsDir(context)
            var removed = 0
            dir.listFiles()?.forEach { if (it.delete()) removed++ }
            return removed
        }
    }
}

/**
 * Download engine (specs/03): primary→mirror chunk fetching with verification,
 * ordered assembly under a 128 MiB memory budget, partial outputs discarded.
 * Re-download from scratch is the resume strategy — downloads are cheap.
 */
class DownloadService(
    private val db: CloudNestDatabase,
    private val connectors: Map<String, ProviderConnector>,
    private val tokens: TokenProvider,
    private val networkGate: NetworkGate = AlwaysAllowed,
    private val memoryBudgetBytes: Long = MEMORY_BUDGET_BYTES,
    /** Health ledger (specs/06 §2.1) — download outcomes count toward the verdict. */
    private val health: HealthTracker? = null,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val fetcher = ChunkFetcher(db, connectors, tokens, delayFn, health)
    private val outputs = ConcurrentHashMap<String, DownloadOutput>()

    fun registerOutput(jobId: String, output: DownloadOutput) {
        outputs[jobId] = output
    }

    /** Queues a download job for a completed file. */
    suspend fun enqueue(fileId: String): String {
        val file = db.fileDao().getById(fileId) ?: throw IllegalArgumentException("no file: $fileId")
        require(file.status == FileStatus.COMPLETE) { "only complete files can be downloaded" }
        val now = clock()
        val jobId = UUID.randomUUID().toString()
        db.transferJobDao().insert(
            TransferJobEntity(
                id = jobId,
                kind = JobKind.DOWNLOAD,
                fileId = fileId,
                status = JobStatus.QUEUED,
                errorCode = null,
                attempts = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return jobId
    }

    suspend fun runJob(jobId: String) {
        val initial = db.transferJobDao().getById(jobId) ?: return
        if (initial.status != JobStatus.QUEUED) return
        db.transferJobDao().update(initial.copy(status = JobStatus.RUNNING, updatedAt = clock()))

        val job = db.transferJobDao().getById(jobId)!!
        val file = job.fileId?.let { db.fileDao().getById(it) }
        val output = outputs.remove(jobId)
        if (file == null || file.status != FileStatus.COMPLETE || output == null) {
            failJob(jobId, DownloadErrorCode.UNKNOWN, output)
            return
        }

        if (!networkGate.isSatisfied()) {
            // specs/03 §5: network loss auto-pauses; resume restarts cleanly.
            db.transferJobDao().update(job.copy(status = JobStatus.PAUSED, updatedAt = clock()))
            output.discardPartial()
            return
        }

        try {
            val chunks = db.fileChunkDao().listByFile(file.id)
            val largest = chunks.maxOfOrNull { it.sizeBytes } ?: 0L
            val maxInFlight = if (largest * 2 <= memoryBudgetBytes) 2 else 1

            val written = output.openStream().use { stream ->
                Assembler.assemble(
                    target = stream,
                    chunks = chunks,
                    maxInFlight = maxInFlight,
                    shouldContinue = { db.transferJobDao().getById(jobId)?.status == JobStatus.RUNNING },
                ) { chunk -> fetcher.fetch(chunk) }
            }

            val finished = db.transferJobDao().getById(jobId)!!
            if (finished.status != JobStatus.RUNNING) {
                // Paused/cancelled between chunks — drop the partial output.
                output.discardPartial()
                return
            }
            if (written != file.sizeBytes) throw DownloadJobException(DownloadErrorCode.FILE_CHANGED)
            db.transferJobDao().update(finished.copy(status = JobStatus.DONE, updatedAt = clock()))
        } catch (e: DownloadJobException) {
            failJob(jobId, e.errorCode, output)
        }
    }

    suspend fun pause(jobId: String) {
        val job = db.transferJobDao().getById(jobId) ?: return
        if (job.status in listOf(JobStatus.QUEUED, JobStatus.RUNNING)) {
            db.transferJobDao().update(job.copy(status = JobStatus.PAUSED, updatedAt = clock()))
        }
    }

    suspend fun resume(jobId: String) {
        val job = db.transferJobDao().getById(jobId) ?: return
        if (job.status == JobStatus.PAUSED) {
            db.transferJobDao().update(job.copy(status = JobStatus.QUEUED, errorCode = null, updatedAt = clock()))
        }
    }

    suspend fun cancel(jobId: String) {
        val job = db.transferJobDao().getById(jobId) ?: return
        if (job.status in listOf(JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.PAUSED)) {
            db.transferJobDao().update(job.copy(status = JobStatus.CANCELLED, updatedAt = clock()))
        }
    }

    private suspend fun failJob(jobId: String, errorCode: String, output: DownloadOutput?) {
        output?.discardPartial()
        db.transferJobDao().getById(jobId)?.let { job ->
            db.transferJobDao().update(job.copy(status = JobStatus.FAILED, errorCode = errorCode, updatedAt = clock()))
        }
    }

    private companion object {
        /** Global in-memory chunk budget (specs/03 §3). */
        const val MEMORY_BUDGET_BYTES: Long = 128L * 1024 * 1024
    }
}
