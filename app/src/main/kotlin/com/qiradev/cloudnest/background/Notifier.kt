package com.qiradev.cloudnest.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.qiradev.cloudnest.MainActivity
import com.qiradev.cloudnest.R
import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.entity.NotificationType
import com.qiradev.cloudnest.data.entity.Providers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Every OS notification goes through here (specs/05 §6). Two channels —
 * `progress` (foreground transfer) and `events` (feed echo). The phone shows
 * only what the settings allow; the spec-fixed defaults (specs/05 §2.2) are
 * failures + quota warnings ON, successful uploads OFF. The in-app feed keeps
 * everything regardless of this policy.
 */
class Notifier(val context: Context) {

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, context.getString(R.string.channel_events), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** One event notification; silently skipped when the permission is missing. */
    fun postEvent(type: String, text: String, retry: PendingIntent? = null) {
        if (!showsOnPhone(type)) return
        post(CHANNEL_EVENTS, EVENT_NOTIFICATION_ID, buildEventNotification(text, retry))
    }

    /** "Retry" action target for a failed-upload notice (specs/05 §2.2) — requeues the job. */
    fun retryIntent(jobId: String): PendingIntent = PendingIntent.getForegroundService(
        context,
        0,
        Intent(context, UploadForegroundService::class.java)
            .setAction(UploadForegroundService.ACTION_RETRY_JOB)
            .putExtra(UploadForegroundService.EXTRA_JOB_ID, jobId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Human provider name for notice text; unknown ids fall back to the raw value. */
    fun providerLabel(provider: String): String = when (provider) {
        Providers.GOOGLE_DRIVE -> context.getString(R.string.provider_google_drive)
        Providers.DROPBOX -> context.getString(R.string.provider_dropbox)
        Providers.ONEDRIVE -> context.getString(R.string.provider_onedrive)
        Providers.PCLOUD -> context.getString(R.string.provider_pcloud)
        Providers.MEGA -> context.getString(R.string.provider_mega)
        else -> provider
    }

    /** Builds the persistent transfer notification — the foreground service posts it. */
    fun progressNotification(text: String, percent: Int?, pauseIntent: PendingIntent?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(context.getString(R.string.notif_uploading_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentText(text)
        if (percent != null) builder.setProgress(100, percent.coerceIn(0, 100), false)
        pauseIntent?.let { builder.addAction(0, context.getString(R.string.notif_action_stop), it) }
        return builder.build()
    }

    fun clearProgress() {
        NotificationManagerCompat.from(context).cancel(PROGRESS_NOTIFICATION_ID)
    }

    private fun buildEventNotification(text: String, retry: PendingIntent?): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
        retry?.let { builder.addAction(0, context.getString(R.string.action_retry), it) }
        return builder.build()
    }

    private fun post(channel: String, id: Int, notification: Notification) {
        // specs/05 §3.1: the refusal path is a no-op — and a user revoking the
        // permission mid-flight only makes `notify` throw, which runCatching swallows.
        val permitted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        if (!permitted) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    companion object {
        const val CHANNEL_PROGRESS = "progress"
        const val CHANNEL_EVENTS = "events"
        const val PROGRESS_NOTIFICATION_ID = 1
        const val EVENT_NOTIFICATION_ID = 2

        /** specs/05 §2.2 defaults: failures + quota warnings reach the phone. */
        val PHONE_DEFAULT_ON: Set<String> = setOf(
            NotificationType.UPLOAD_FAILED,
            NotificationType.MIRROR_FAILED,
            NotificationType.QUOTA_STALE,
            NotificationType.TOKEN_EXPIRED,
        )

        fun showsOnPhone(type: String): Boolean = type in PHONE_DEFAULT_ON
    }
}

/**
 * Bridges the engine's notification feed (DB rows) to the OS notification
 * shade. The engine stays Android-free — it only writes feed rows; this relay
 * (specs/05 §2) mirrors new rows onto the phone under the [Notifier] policy.
 */
object NotificationRelay {

    fun start(db: CloudNestDatabase, notifier: Notifier, scope: CoroutineScope): Job {
        val since = System.currentTimeMillis()
        return scope.launch {
            db.notificationDao().observeNewSince(since).collectLatest { rows ->
                rows.forEach { row ->
                    val payload = runCatching { JSONObject(row.payload) }.getOrNull()
                    val file = payload?.optString("file")
                        ?.takeIf { it.isNotBlank() && it != "null" }
                    val provider = payload?.optString("provider")
                        ?.takeIf { it.isNotBlank() && it != "null" }
                        ?.let { notifier.providerLabel(it) }
                    val text = when (row.type) {
                        NotificationType.UPLOAD_DONE ->
                            notifier.context.getString(R.string.notif_upload_done, file ?: "")
                        NotificationType.UPLOAD_FAILED ->
                            notifier.context.getString(R.string.notif_upload_failed, file ?: "")
                        NotificationType.MIRROR_DONE ->
                            notifier.context.getString(R.string.notif_mirror_done, file ?: "")
                        NotificationType.MIRROR_FAILED ->
                            notifier.context.getString(R.string.notif_mirror_failed, file ?: "")
                        NotificationType.QUOTA_STALE ->
                            notifier.context.getString(R.string.notif_quota_stale, provider ?: "")
                        NotificationType.TRASH_PURGED ->
                            notifier.context.getString(R.string.notif_trash_purged)
                        NotificationType.TOKEN_EXPIRED ->
                            notifier.context.getString(R.string.notif_token_expired, provider ?: "")
                        else -> return@forEach
                    }
                    // specs/05 §2.2: failed uploads carry a "Retry" action that
                    // requeues the job; every other event posts plain.
                    val retryJobId = payload?.optString("job")?.takeIf { it.isNotBlank() && it != "null" }
                    val retry = if (row.type == NotificationType.UPLOAD_FAILED && retryJobId != null) {
                        notifier.retryIntent(retryJobId)
                    } else {
                        null
                    }
                    notifier.postEvent(row.type, text, retry)
                }
            }
        }
    }
}
