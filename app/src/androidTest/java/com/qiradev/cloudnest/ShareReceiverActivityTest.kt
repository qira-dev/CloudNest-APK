package com.qiradev.cloudnest

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qiradev.cloudnest.background.ShareReceiverActivity
import com.qiradev.cloudnest.data.entity.JobKind
import com.qiradev.cloudnest.data.entity.JobStatus
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Share target end-to-end on a real Android (specs/05 §4 + §6): Share →
 * ShareReceiverActivity queues an upload job and stages a cache copy.
 */
@RunWith(AndroidJUnit4::class)
class ShareReceiverActivityTest {

    @Test
    fun shareSendQueuesAnUploadJob() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Instrumented tests share the app DB — count, don't assume an empty queue.
        val jobsBefore = countUploadJobs()

        val staging = File(context.cacheDir, "shared-uploads").apply { mkdirs() }
        val payload = File(staging, "instrumented-clip-${System.currentTimeMillis()}.mp4").apply {
            delete()
            writeBytes(ByteArray(2_048) { (it % 127).toByte() })
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", payload)
        val send = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        ActivityScenario.launch<ShareReceiverActivity>(send)

        val deadline = System.currentTimeMillis() + 15_000
        while (countUploadJobs() <= jobsBefore && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
        }
        val total = countUploadJobs()
        assertTrue("expected a new upload job after share, saw $total (before: $jobsBefore)", total > jobsBefore)
    }

    private fun countUploadJobs(): Int = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dao = AppServices.db(context).transferJobDao()
        var count = 0
        for (status in listOf(JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.FAILED, JobStatus.DONE)) {
            count += dao.listByKindAndStatus(JobKind.UPLOAD, status).size
        }
        count
    }
}
