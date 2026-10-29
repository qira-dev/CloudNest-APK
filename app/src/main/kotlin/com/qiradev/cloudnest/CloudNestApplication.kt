package com.qiradev.cloudnest

import android.app.Application
import androidx.work.Configuration
import com.qiradev.cloudnest.background.BackgroundJobs
import com.qiradev.cloudnest.background.NotificationRelay
import com.qiradev.cloudnest.background.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide entry (specs/05): registers the recurring WorkManager jobs and
 * starts the feed→OS notification relay. The engine graph itself stays lazy
 * through [AppServices]. WorkManager is initialized on demand
 * ([Configuration.Provider]) — the manifest's default initializer is removed,
 * which also keeps Robolectric local tests working.
 */
class CloudNestApplication : Application(), Configuration.Provider {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        BackgroundJobs.schedule(this)
        val notifier = Notifier(this).also { it.ensureChannels() }
        NotificationRelay.start(AppServices.db(this), notifier, appScope)
    }
}
