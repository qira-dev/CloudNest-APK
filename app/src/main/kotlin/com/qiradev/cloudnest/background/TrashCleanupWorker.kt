package com.qiradev.cloudnest.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.qiradev.cloudnest.AppServices
import com.qiradev.cloudnest.data.CloudNestDatabase

/**
 * Daily trash sweep (specs/05 §1): purges everything past `purge_at` through
 * [com.qiradev.cloudnest.engine.archive.ArchiveService.purgeDue] — the engine
 * owns the deletion_attempts ledger, cloud-object deletes and the feed rows;
 * the OS echo rides the notification relay. Idempotent: purgeDue only ever
 * touches what is due.
 */
class TrashCleanupWorker(
    context: Context,
    params: WorkerParameters,
    private val db: CloudNestDatabase = AppServices.db(context),
    private val archive: com.qiradev.cloudnest.engine.archive.ArchiveService? = null,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        (archive ?: AppServices.archiveService(db)).purgeDue()
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "trash_cleanup"
    }
}
