package com.qiradev.cloudnest.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.qiradev.cloudnest.AppServices
import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.entity.NotificationEntity
import com.qiradev.cloudnest.data.entity.NotificationType
import com.qiradev.cloudnest.data.entity.ProviderAccountStatus
import com.qiradev.cloudnest.data.entity.QuotaStatus
import com.qiradev.cloudnest.engine.providers.ProviderErrorCode
import com.qiradev.cloudnest.engine.providers.ProviderException
import java.util.UUID
import org.json.JSONObject

/**
 * Hourly-round quota clock (specs/05 §1): every connected account gets its
 * quota re-read (specs/04 §4 refresh path); accounts not successfully observed
 * for 3 days flip to `stale` with a `quota_stale` notice. Idempotent — every
 * run recomputes from the ledger.
 */
class QuotaRefreshWorker(
    context: Context,
    params: WorkerParameters,
    private val db: CloudNestDatabase = AppServices.db(context),
    private val connections: com.qiradev.cloudnest.engine.providers.AccountConnectionManager? = null,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val manager = connections ?: AppServices.connectionManager(db)

        val accounts = db.providerAccountDao().getAll()
            .filter { it.status == ProviderAccountStatus.CONNECTED }
        val observedBefore = accounts.associate { it.id to it.quotaObservedAt }

        accounts.forEach { account ->
            runCatching { manager.refreshQuota(account.id) }.onFailure { error ->
                // specs/05 §2.2 — token expiry surfaces as a "sign-in required"
                // notice, at most one unread row per provider (idempotent runs
                // must not stack the feed with duplicates).
                if (error is ProviderException && error.code == ProviderErrorCode.AUTH_EXPIRED) {
                    val provider = account.provider
                    val already = db.notificationDao()
                        .listUnreadByType(NotificationType.TOKEN_EXPIRED)
                        .any { runCatching { JSONObject(it.payload).optString("provider") == provider }
                            .getOrDefault(false) }
                    if (!already) {
                        db.notificationDao().insert(
                            NotificationEntity(
                                id = UUID.randomUUID().toString(),
                                type = NotificationType.TOKEN_EXPIRED,
                                payload = """{"provider":"$provider"}""",
                                read = 0,
                                createdAt = now,
                            ),
                        )
                        db.notificationDao().trimTo(100)
                    }
                }
            }
        }

        // 3 days without a successful observation → stale + notice (specs/05 §1).
        // A refresh that just succeeded writes a fresh observed_at, so staleness
        // only bites accounts whose observation is still the old one (failing
        // provider or a long device-off gap). observed_at == 0 means "never
        // observed" — that is `unknown`, not stale.
        val cutoff = now - STALE_AFTER_MS
        accounts.forEach { account ->
            val current = db.providerAccountDao().getById(account.id) ?: return@forEach
            val lastObservation = current.quotaObservedAt
            val unchanged = lastObservation == observedBefore[account.id]
            if (unchanged && lastObservation in 1 until cutoff && current.quotaStatus != QuotaStatus.STALE) {
                db.providerAccountDao().update(current.copy(quotaStatus = QuotaStatus.STALE))
                db.notificationDao().insert(
                    NotificationEntity(
                        id = UUID.randomUUID().toString(),
                        type = NotificationType.QUOTA_STALE,
                        payload = """{"provider":"${current.provider}"}""",
                        read = 0,
                        createdAt = now,
                    ),
                )
                db.notificationDao().trimTo(100)
            }
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "quota_refresh"
        const val STALE_AFTER_MS = 3L * 24 * 60 * 60 * 1000
    }
}
