package com.qiradev.cloudnest.background

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.qiradev.cloudnest.AppServices
import com.qiradev.cloudnest.R
import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.entity.JobKind
import com.qiradev.cloudnest.data.entity.PlacementStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground upload service (specs/02 §6, specs/05 §2.1): drains the queued
 * upload and mirror jobs with a persistent progress notification (file name +
 * percent + stop button) so transfers survive the screen turning off. The
 * queue lives in the DB — the system may kill and restart freely; whatever is
 * left is revived by [JobReviveWorker].
 */
class UploadForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notifier by lazy { Notifier(this) }
    private var currentJobId: String? = null
    private var progressJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannels()
        postProgressNotification(null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_CURRENT -> {
                val jobId = currentJobId
                if (jobId != null) {
                    scope.launch {
                        AppServices.uploadService(AppServices.db(applicationContext)).pause(jobId)
                    }
                }
            }
            ACTION_RETRY_JOB -> {
                // Notification "Retry" (specs/05 §2.2): requeue the failed job,
                // then drain — the loop picks it up (or a live loop already will).
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                if (jobId != null) {
                    scope.launch {
                        AppServices.uploadService(AppServices.db(applicationContext)).retry(jobId)
                    }
                }
                scope.launch { drain() }
            }
            else -> scope.launch { drain() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        progressJob?.cancel()
        notifier.clearProgress()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun drain() {
        val db = AppServices.db(applicationContext)
        val upload = AppServices.uploadService(db)
        val mirror = AppServices.mirrorService(db)
        TransferGate.own {
            if (draining) return@own
            draining = true
            try {
                // Startup recovery once per process, under the gate (specs/02 §7):
                // orphaned `running` jobs go back to queued before anything runs.
                if (!recovered) {
                    upload.recoverAtStartup()
                    mirror.recoverAtStartup()
                    recovered = true
                }
                while (true) {
                    val job = db.transferJobDao().nextQueued(JobKind.UPLOAD) ?: break
                    currentJobId = job.id
                    if (!UploadDrivers.ensureSource(applicationContext, db, upload, job)) {
                        UploadDrivers.failWithoutSource(db, job)
                        currentJobId = null
                        continue
                    }
                    val progress = startProgressPolling(db, job.fileId)
                    upload.runJob(job.id)
                    progress?.cancel()
                    currentJobId = null
                    job.fileId?.let { UploadDrivers.cleanupAfterTerminal(db, job.id, it) }
                }
                while (true) {
                    val job = db.transferJobDao().nextQueued(JobKind.MIRROR) ?: break
                    mirror.runJob(job.id)
                }
            } finally {
                draining = false
            }
        }
        stopSelf()
    }

    private fun startProgressPolling(db: CloudNestDatabase, fileId: String?): Job? {
        if (fileId == null) return null
        return scope.launch {
            while (isActive) {
                val file = db.fileDao().getById(fileId)
                val total = file?.chunkCount ?: 0
                if (file != null && total > 0) {
                    val processed = db.chunkPlacementDao().listByFile(fileId).count {
                        it.status == PlacementStatus.VERIFIED || it.status == PlacementStatus.UPLOADED
                    }
                    postProgressNotification(file.name to (processed * 100 / total))
                }
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun pauseIntent(): PendingIntent = PendingIntent.getService(
        this,
        0,
        Intent(this, UploadForegroundService::class.java).setAction(ACTION_PAUSE_CURRENT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun postProgressNotification(progress: Pair<String, Int>?) {
        val percent = progress?.second
        val text = progress?.first?.let { name ->
            if (percent != null) getString(R.string.notif_upload_progress, name, percent) else name
        } ?: getString(R.string.notif_upload_starting)
        // A foreground service must call startForeground promptly and on every
        // update; ServiceCompat handles the pre-Q (API 26–28) path as well.
        ServiceCompat.startForeground(
            this,
            Notifier.PROGRESS_NOTIFICATION_ID,
            notifier.progressNotification(text, percent, pauseIntent()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val ACTION_PAUSE_CURRENT = "com.qiradev.cloudnest.action.PAUSE_UPLOAD"
        const val ACTION_RETRY_JOB = "com.qiradev.cloudnest.action.RETRY_JOB"
        const val EXTRA_JOB_ID = "job_id"

        @Volatile
        var draining: Boolean = false
            private set

        @Volatile
        private var recovered = false

        const val PROGRESS_INTERVAL_MS = 500L

        /** UI/share entry point: start the service foregrounded (callers are in the foreground). */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, UploadForegroundService::class.java))
        }
    }
}
