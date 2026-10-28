package com.qiradev.cloudnest.engine.download

import com.qiradev.cloudnest.data.buildTestDb
import com.qiradev.cloudnest.data.entity.ChunkPlacementEntity
import com.qiradev.cloudnest.data.entity.JobStatus
import com.qiradev.cloudnest.data.entity.PlacementRole
import com.qiradev.cloudnest.data.entity.PlacementStatus
import com.qiradev.cloudnest.engine.providers.TokenPair
import com.qiradev.cloudnest.engine.upload.ChunkMath
import com.qiradev.cloudnest.engine.upload.InMemoryFileSource
import com.qiradev.cloudnest.engine.upload.UploadTestHarness
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * specs/10 DownloadTest — byte-for-byte round trip, mirror fallback, ordered
 * assembly with parallel fetch, memory budget, partial-output discard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadTest {

    private val bytes = ByteArray(17 * 1024 * 1024) { (it % 251).toByte() } // 3 chunks @ 8 MiB
    private lateinit var uploadHarness: UploadTestHarness
    private lateinit var fileId: String

    private class MemoryOutput : DownloadOutput {
        val buffer = java.io.ByteArrayOutputStream()
        var discarded = false

        override fun openStream(): OutputStream = buffer
        override fun discardPartial() {
            discarded = true
            buffer.reset()
        }

        fun bytes(): ByteArray = buffer.toByteArray()
    }

    @Before
    fun setUp(): Unit = runBlocking {
        uploadHarness = UploadTestHarness(db = buildTestDb(), chunkSizeMib = 8)
        uploadHarness.addAccount("acc-a", freeBytes = 100 * ChunkMath.MIB)
        uploadHarness.addAccount("acc-b", freeBytes = 100 * ChunkMath.MIB)
        val jobId = uploadHarness.enqueueBlocking(InMemoryFileSource(bytes), "video.mp4")
        uploadHarness.service.runJob(jobId)
        fileId = uploadHarness.db.transferJobDao().getById(jobId)!!.fileId!!
    }

    private fun newService(memoryBudgetBytes: Long = 128L * 1024 * 1024) = DownloadService(
        db = uploadHarness.db,
        connectors = mapOf("google_drive" to uploadHarness.connector),
        tokens = { TokenPair("t", null, Long.MAX_VALUE) },
        memoryBudgetBytes = memoryBudgetBytes,
        delayFn = { /* tests must not sleep through the real backoff */ },
    )

    private suspend fun runDownload(service: DownloadService): Pair<String, MemoryOutput> {
        val jobId = service.enqueue(fileId)
        val output = MemoryOutput()
        service.registerOutput(jobId, output)
        service.runJob(jobId)
        return jobId to output
    }

    @Test
    fun roundTripIsByteForByte(): Unit = runBlocking {
        val service = newService()
        val (jobId, output) = runDownload(service)

        assertEquals(JobStatus.DONE, uploadHarness.db.transferJobDao().getById(jobId)!!.status)
        assertTrue(output.bytes().contentEquals(bytes))
    }

    @Test
    fun parallelFetchNeverExceedsTwoAndOrderStaysCorrect(): Unit = runBlocking {
        uploadHarness.connector.downloadSleepMs = 5
        val service = newService()
        val (jobId, output) = runDownload(service)

        assertEquals(JobStatus.DONE, uploadHarness.db.transferJobDao().getById(jobId)!!.status)
        assertTrue(output.bytes().contentEquals(bytes))
        assertTrue(
            "max concurrent was ${uploadHarness.connector.maxConcurrentDownloads}",
            uploadHarness.connector.maxConcurrentDownloads in 1..2,
        )
    }

    @Test
    fun tinyMemoryBudgetForcesSequentialFetching(): Unit = runBlocking {
        uploadHarness.connector.downloadSleepMs = 5
        // Budget 8 MiB < 2 × 8 MiB chunk → single in-flight fetch (specs/03 §3).
        val service = newService(memoryBudgetBytes = ChunkMath.MIB * 8)
        runDownload(service)

        assertEquals(1, uploadHarness.connector.maxConcurrentDownloads)
    }

    @Test
    fun primaryFailureFallsBackToMirror(): Unit = runBlocking {
        addMirrorCopies()
        // Only the PRIMARY copies "fail" on the network — mirrors must take over.
        uploadHarness.connector.failingRemoteIds.addAll(
            uploadHarness.db.chunkPlacementDao().listByFile(fileId)
                .filter { it.role == PlacementRole.PRIMARY }
                .mapNotNull { it.remoteId },
        )

        val service = newService()
        val (jobId, output) = runDownload(service)

        assertEquals(JobStatus.DONE, uploadHarness.db.transferJobDao().getById(jobId)!!.status)
        assertTrue(output.bytes().contentEquals(bytes))
    }

    @Test
    fun hashMismatchOnBothCopiesFailsAndMarksSuspicious(): Unit = runBlocking {
        addMirrorCopies()
        uploadHarness.connector.corruptDownloadsRemaining = 100 // every copy arrives corrupt

        val service = newService()
        val (jobId, output) = runDownload(service)

        val job = uploadHarness.db.transferJobDao().getById(jobId)!!
        assertEquals(JobStatus.FAILED, job.status)
        assertEquals(DownloadErrorCode.CHUNK_HASH_MISMATCH, job.errorCode)
        assertTrue(output.discarded)
        // At least one copy is marked suspicious; parallel cancellation may leave
        // a concurrently-failing chunk unmarked — that is expected.
        val suspicious = uploadHarness.db.chunkPlacementDao().listByFile(fileId)
            .filter { it.status == PlacementStatus.FAILED }
        assertTrue(suspicious.isNotEmpty())
        val failingChunkId = uploadHarness.db.fileChunkDao().listByFile(fileId).first().id
        assertTrue(suspicious.any { it.chunkId == failingChunkId })
    }

    @Test
    fun missingPlacementFailsWithChunkUnavailable(): Unit = runBlocking {
        uploadHarness.db.chunkPlacementDao().listByFile(fileId)
            .forEach { uploadHarness.db.chunkPlacementDao().delete(it.id) }

        val service = newService()
        val (jobId, output) = runDownload(service)

        val job = uploadHarness.db.transferJobDao().getById(jobId)!!
        assertEquals(JobStatus.FAILED, job.status)
        assertEquals(DownloadErrorCode.CHUNK_UNAVAILABLE, job.errorCode)
        assertTrue(output.discarded)
    }

    @Test
    fun cancelMidRunDiscardsThePartialOutput(): Unit = runBlocking {
        uploadHarness.connector.downloadSleepMs = 50
        val service = newService(memoryBudgetBytes = ChunkMath.MIB * 8)
        val jobId = service.enqueue(fileId)
        val output = MemoryOutput()
        service.registerOutput(jobId, output)

        val runner = async(Dispatchers.IO) { service.runJob(jobId) }
        delay(80)
        service.cancel(jobId)
        runner.await()

        assertEquals(JobStatus.CANCELLED, uploadHarness.db.transferJobDao().getById(jobId)!!.status)
        assertTrue(output.discarded)
        assertTrue(output.bytes().isEmpty())
    }

    @Test
    fun networkLossAtStartAutoPausesAndResumeRunsClean(): Unit = runBlocking {
        val service = newService()
        val jobId = service.enqueue(fileId)
        val output = MemoryOutput()
        service.registerOutput(jobId, output)

        // Simulate a closed gate at start (network gone).
        val closedGate = DownloadService(
            db = uploadHarness.db,
            connectors = mapOf("google_drive" to uploadHarness.connector),
            tokens = { TokenPair("t", null, Long.MAX_VALUE) },
            networkGate = object : com.qiradev.cloudnest.engine.upload.NetworkGate {
                override fun isSatisfied() = false
            },
        )
        closedGate.registerOutput(jobId, output)
        closedGate.runJob(jobId)
        assertEquals(JobStatus.PAUSED, uploadHarness.db.transferJobDao().getById(jobId)!!.status)
        assertTrue(output.discarded)

        // Gate reopens (real service) — resume restarts from scratch.
        service.resume(jobId)
        service.registerOutput(jobId, output)
        service.runJob(jobId)
        assertEquals(JobStatus.DONE, uploadHarness.db.transferJobDao().getById(jobId)!!.status)
        assertTrue(output.bytes().contentEquals(bytes))
    }

    private suspend fun addMirrorCopies() {
        uploadHarness.db.chunkPlacementDao().listByFile(fileId).forEach { primary ->
            val chunk = uploadHarness.db.fileChunkDao().getById(primary.chunkId)!!
            val data = uploadHarness.connector.chunks.getValue(primary.remoteId!!.removePrefix("remote-"))
            val mirrorRemoteId = uploadHarness.connector.uploadChunk(
                TokenPair("t", null, Long.MAX_VALUE),
                "fake-root",
                "mirror-${chunk.id}.bin",
                data,
            )
            uploadHarness.db.chunkPlacementDao().insert(
                ChunkPlacementEntity(
                    id = UUID.randomUUID().toString(),
                    chunkId = chunk.id,
                    accountId = "acc-b",
                    role = PlacementRole.MIRROR,
                    remoteId = mirrorRemoteId,
                    status = PlacementStatus.VERIFIED,
                    uploadedAt = null,
                ),
            )
        }
    }
}
