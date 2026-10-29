package com.qiradev.cloudnest.ui

import com.qiradev.cloudnest.AppServices
import com.qiradev.cloudnest.data.buildTestDb
import com.qiradev.cloudnest.data.entity.FolderEntity
import com.qiradev.cloudnest.data.entity.FileEntity
import com.qiradev.cloudnest.data.entity.FileStatus
import com.qiradev.cloudnest.data.entity.TransferJobEntity
import com.qiradev.cloudnest.data.entity.JobKind
import com.qiradev.cloudnest.data.entity.JobStatus
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Queue screen controller (specs/09 screen 7): rows carry the file name and
 * upload progress; the Wi-Fi-only status flag reflects the settings default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QueueUiControllerTest {

    private val dispatcher = Executors.newSingleThreadExecutor().also {
        Runtime.getRuntime().addShutdownHook(Thread { it.shutdown() })
    }

    private fun <T> poll(timeoutMs: Long = 5_000, check: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            check()?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    @Test
    fun rowsShowFileNameAndWifiOnlyFlag() = runBlocking {
        val db = buildTestDb()
        db.folderDao().insert(
            FolderEntity("folder-1", null, "docs", 1, null, null),
        )
        db.fileDao().insert(
            FileEntity(
                "file-1", "folder-1", "a.mp4", 1_000, "video/mp4", 64, 1, "h",
                FileStatus.UPLOADING, 1, 1, null, null,
            ),
        )
        db.transferJobDao().insert(
            TransferJobEntity("job-1", JobKind.UPLOAD, "file-1", JobStatus.QUEUED, null, 0, 1, 1),
        )
        val controller = QueueUiController(
            db,
            AppServices.uploadService(db),
            AppServices.mirrorService(db),
            AppServices.downloadService(db),
            CoroutineScope(dispatcher.asCoroutineDispatcher()),
        )

        controller.refresh()
        val ready = poll { controller.state as? QueueUiState.Ready }
        assertEquals(1, ready.rows.size)
        assertEquals("a.mp4", ready.rows.single().fileName)
        // specs/01 §1.2 default: Wi-Fi-only uploads on → the status line shows it.
        assertTrue(ready.wifiOnly)
    }

    @Test
    fun freshInstallWithNoJobsShowsEmptyReadyState() = runBlocking {
        val db = buildTestDb()
        val controller = QueueUiController(
            db,
            AppServices.uploadService(db),
            AppServices.mirrorService(db),
            AppServices.downloadService(db),
            CoroutineScope(dispatcher.asCoroutineDispatcher()),
        )
        controller.refresh()
        val ready = poll { controller.state as? QueueUiState.Ready }
        assertTrue(ready.rows.isEmpty())
        assertTrue(ready.wifiOnly)
    }
}
