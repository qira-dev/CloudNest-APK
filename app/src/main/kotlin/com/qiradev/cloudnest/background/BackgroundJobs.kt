package com.qiradev.cloudnest.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Registers the three recurring jobs (specs/05 §1). KEEP policy + unique
 * names make scheduling idempotent — calling this on every app start is safe.
 */
object BackgroundJobs {

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniquePeriodicWork(
            QuotaRefreshWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<QuotaRefreshWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
        workManager.enqueueUniquePeriodicWork(
            TrashCleanupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TrashCleanupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build(),
        )
        workManager.enqueueUniquePeriodicWork(
            JobReviveWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<JobReviveWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }
}
