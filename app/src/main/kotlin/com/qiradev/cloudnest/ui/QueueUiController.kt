package com.qiradev.cloudnest.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.entity.FileEntity
import com.qiradev.cloudnest.data.entity.JobKind
import com.qiradev.cloudnest.data.entity.JobStatus
import com.qiradev.cloudnest.data.entity.PlacementStatus
import com.qiradev.cloudnest.data.entity.TransferJobEntity
import com.qiradev.cloudnest.engine.download.DownloadService
import com.qiradev.cloudnest.engine.mirror.MirrorService
import com.qiradev.cloudnest.engine.settings.EngineSettings
import com.qiradev.cloudnest.engine.upload.UploadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One row of the upload queue screen (specs/09 screen 7). */
data class QueueRow(
    val job: TransferJobEntity,
    val fileName: String,
    val progressPercent: Int?,
)

/** Upload queue screen state (specs/09 screen 7). */
sealed interface QueueUiState {
    data object Loading : QueueUiState
    data class Error(val message: String) : QueueUiState
    data class Ready(
        val rows: List<QueueRow>,
        val wifiOnly: Boolean,
    ) : QueueUiState
}

/**
 * Drives the transfer queue (specs/09 screen 7): per-job progress and the
 * pause/resume/cancel/retry controls, plus the Wi-Fi-only status line. Upload
 * and mirror jobs are driven by the background service/revive worker; queued
 * download jobs are driven here with the output the download was started with
 * (a download whose app process died needs a fresh start from the file detail).
 */
class QueueUiController(
    private val db: CloudNestDatabase,
    private val upload: UploadService,
    private val mirror: MirrorService,
    private val download: DownloadService,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    var state: QueueUiState by mutableStateOf(QueueUiState.Loading)
        private set

    fun refresh() {
        scope.launch {
            state = try {
                val jobRows = db.transferJobDao().listRecent(200).filter { it.kind != JobKind.PURGE }
                val fileIds = jobRows.mapNotNull { it.fileId }
                // Room rejects `IN ()` on an empty list — a fresh install lands here.
                val files = if (fileIds.isEmpty()) {
                    emptyMap()
                } else {
                    db.fileDao().getAllByIds(fileIds).associateBy { it.id }
                }
                QueueUiState.Ready(
                    rows = jobRows.map { job -> buildRow(job, files) },
                    wifiOnly = EngineSettings.wifiOnlyUpload(db),
                )
            } catch (e: Exception) {
                QueueUiState.Error(e.message ?: "unknown")
            }
        }
    }

    private suspend fun buildRow(job: TransferJobEntity, files: Map<String, FileEntity>): QueueRow {
        val file = job.fileId?.let { files[it] }
        val percent = when {
            job.kind == JobKind.UPLOAD && file != null && file.chunkCount > 0 -> {
                val placements = db.chunkPlacementDao().listByFile(file.id)
                val done = placements.count {
                    it.status == PlacementStatus.VERIFIED || it.status == PlacementStatus.UPLOADED
                }
                done * 100 / file.chunkCount
            }
            job.status == JobStatus.DONE -> 100
            else -> null
        }
        return QueueRow(job, file?.name.orEmpty(), percent)
    }

    fun pause(jobId: String) = jobAction(jobId) { upload.pause(it) }
    fun resume(jobId: String) = jobAction(jobId) { upload.resume(it) }
    fun cancel(jobId: String, kind: String) = jobAction(jobId) {
        if (kind == JobKind.DOWNLOAD) download.cancel(it) else upload.cancel(it)
    }

    /** Retry: failed upload jobs requeue through the engine; mirror through its service. */
    fun retry(jobId: String, kind: String) = jobAction(jobId) {
        if (kind == JobKind.MIRROR) mirror.retry(it) else upload.retry(it)
    }

    private fun jobAction(jobId: String, action: suspend (String) -> Unit) {
        scope.launch {
            runCatching { action(jobId) }
            refresh()
        }
    }
}
