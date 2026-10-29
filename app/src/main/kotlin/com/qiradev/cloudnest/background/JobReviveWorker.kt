package com.qiradev.cloudnest.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.qiradev.cloudnest.AppServices
import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.entity.JobKind
import com.qiradev.cloudnest.data.entity.JobStatus
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fifteen-minute heartbeat (specs/05 §1): when the system killed the process
 * mid-transfer, jobs sit queued/orphaned-running with nobody driving them.
 * Under the [TransferGate] (so a live foreground run is never disturbed), the
 * worker requeues orphans and drains what it can inside its time budget.
 */
class JobReviveWorker(
    context: Context,
    params: WorkerParameters,
    private val db: CloudNestDatabase = AppServices.db(context),
    private val services: Pair<
        com.qiradev.cloudnest.engine.upload.UploadService,
        com.qiradev.cloudnest.engine.mirror.MirrorService,
        >? = null,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pending = (
            db.transferJobDao().listByStatus(JobStatus.QUEUED) +
                db.transferJobDao().listByStatus(JobStatus.RUNNING)
            ).filter { it.kind == JobKind.UPLOAD || it.kind == JobKind.MIRROR }
        if (pending.isEmpty() || UploadForegroundService.draining) {
            return Result.success()
        }
        withTimeoutOrNull(BUDGET_MS) {
            TransferGate.own {
                val (upload, mirror) = services
                    ?: AppServices.liveTransferServices(applicationContext, db)
                upload.recoverAtStartup()
                mirror.recoverAtStartup()
                while (true) {
                    val job = db.transferJobDao().nextQueued(JobKind.UPLOAD) ?: break
                    if (!UploadDrivers.ensureSource(applicationContext, db, upload, job)) {
                        UploadDrivers.failWithoutSource(db, job)
                        continue
                    }
                    upload.runJob(job.id)
                    job.fileId?.let { UploadDrivers.cleanupAfterTerminal(db, job.id, it) }
                }
                while (true) {
                    val job = db.transferJobDao().nextQueued(JobKind.MIRROR) ?: break
                    mirror.runJob(job.id)
                }
            }
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "job_revive"

        /** Stay well inside WorkManager's ~10-minute stop window. */
        const val BUDGET_MS = 8L * 60 * 1000
    }
}
