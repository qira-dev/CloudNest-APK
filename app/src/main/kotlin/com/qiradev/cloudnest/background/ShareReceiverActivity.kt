package com.qiradev.cloudnest.background

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.qiradev.cloudnest.AppServices
import kotlinx.coroutines.launch

/**
 * Share target (specs/05 §4): any app's Share → CloudNest. The incoming URIs
 * are copied into the private cache immediately (their grants can be revoked),
 * queued as upload jobs aimed at the root folder, and the foreground service
 * takes over — uploads run even after this activity is long gone.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val received = intent
        lifecycleScope.launch {
            try {
                val uris = incomingUris(received)
                if (uris.isNotEmpty()) {
                    val db = AppServices.db(applicationContext)
                    val rootId = AppServices.archiveService(db).ensureRootFolder().id
                    // Settings-aware enqueue (specs/01 §1.2): the stored chunk size lands on the file row.
                    val (upload, _) = AppServices.liveTransferServices(applicationContext, db)
                    val queued = ShareUploads.enqueueFromShareUris(
                        applicationContext,
                        db,
                        upload,
                        uris,
                        rootId,
                    )
                    if (queued.isNotEmpty()) UploadForegroundService.start(applicationContext)
                }
            } catch (_: Exception) {
                // A broken share must never crash the sender's app; the share is dropped.
            }
            finish()
        }
    }

    private fun incomingUris(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
}
