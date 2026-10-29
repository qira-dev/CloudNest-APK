package com.qiradev.cloudnest.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.engine.download.CacheFileOutput
import com.qiradev.cloudnest.engine.download.DownloadOutput
import com.qiradev.cloudnest.engine.download.DownloadService
import com.qiradev.cloudnest.background.TransferGate
import java.io.OutputStream
import kotlinx.coroutines.launch

/** Download progress for the file detail screen (specs/03 §4, specs/09 screen 8). */
sealed interface DownloadUiState {
    data class Running(val jobId: String) : DownloadUiState

    /** [filePath] is set for the cache export (share offer); null for a SAF save. */
    data class Done(val jobId: String, val filePath: String?) : DownloadUiState
    data class Failed(val errorCode: String) : DownloadUiState
}

/** SAF save-to target (specs/03 §4.2): stream straight to the picked document. */
class SafDocumentOutput(private val stream: OutputStream) : DownloadOutput {
    override fun openStream(): OutputStream = stream
    override fun discardPartial() {
        runCatching { stream.close() }
    }
}

/** Drives downloads started from the file detail screen (specs/09 screen 8). */
class DownloadUiController(
    private val context: Context,
    private val db: CloudNestDatabase,
    private val download: DownloadService,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {

    var state: DownloadUiState? by mutableStateOf(null)
        private set

    fun clear() {
        state = null
    }

    /** "খোলা/শেয়ার" path (specs/03 §4.1): assemble into the cache, then offer share. */
    fun downloadToCache(fileId: String) {
        if (state is DownloadUiState.Running) return
        scope.launch {
            try {
                val fileName = db.fileDao().getById(fileId)?.name ?: return@launch
                val output = CacheFileOutput.newExport(context, fileName)
                runDownload(fileId, output, fileName)
            } catch (e: com.qiradev.cloudnest.engine.download.DownloadJobException) {
                state = DownloadUiState.Failed(e.errorCode)
            } catch (e: IllegalStateException) {
                state = DownloadUiState.Failed("unknown")
            }
        }
    }

    /** "সেভ" path (specs/03 §4.2): stream into the user-picked SAF document. */
    fun downloadToSaf(fileId: String, target: Uri) {
        if (state is DownloadUiState.Running) return
        scope.launch {
            try {
                val stream = context.contentResolver.openOutputStream(target, "wt")
                    ?: error("cannot open save target")
                val output = SafDocumentOutput(stream)
                val fileName = db.fileDao().getById(fileId)?.name ?: "export"
                runDownload(fileId, output, fileName)
            } catch (e: com.qiradev.cloudnest.engine.download.DownloadJobException) {
                state = DownloadUiState.Failed(e.errorCode)
            } catch (e: IllegalStateException) {
                state = DownloadUiState.Failed("unknown")
            }
        }
    }

    private suspend fun runDownload(fileId: String, output: DownloadOutput, fileName: String) {
        val jobId = download.enqueue(fileId)
        download.registerOutput(jobId, output)
        state = DownloadUiState.Running(jobId)
        // Downloads have no background driver (specs/03 §5 — resume is a fresh
        // start anyway), so the UI drives the job under the shared transfer gate.
        TransferGate.own {
            download.runJob(jobId)
        }
        state = when (db.transferJobDao().getById(jobId)?.status) {
            com.qiradev.cloudnest.data.entity.JobStatus.DONE -> DownloadUiState.Done(
                jobId,
                (output as? CacheFileOutput)?.let { exportsPath(fileName) },
            )
            else -> DownloadUiState.Failed("cancelled")
        }
    }

    private fun exportsPath(fileName: String): String =
        java.io.File(CacheFileOutput.exportsDir(context), fileName).absolutePath
}
