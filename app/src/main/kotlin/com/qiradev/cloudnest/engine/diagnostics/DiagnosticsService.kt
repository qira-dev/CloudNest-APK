package com.qiradev.cloudnest.engine.diagnostics

import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.dao.FailureRow
import com.qiradev.cloudnest.data.entity.JobStatus
import com.qiradev.cloudnest.data.entity.ProviderAccountEntity
import com.qiradev.cloudnest.data.entity.TransferJobEntity
import com.qiradev.cloudnest.engine.health.Health
import com.qiradev.cloudnest.engine.health.HealthTracker
import com.qiradev.cloudnest.engine.providers.AccountConnectionManager
import com.qiradev.cloudnest.engine.providers.ProviderException
import com.qiradev.cloudnest.background.TransferGate

/** One account row of the diagnostics screen (specs/06 §3.1). */
data class AccountDiagnostics(
    val account: ProviderAccountEntity,
    val verdict: Health,
    val lastErrorCode: String?,
    val reservedBytes: Long,
    val recentFailureCount: Int,
)

/** Result of the per-account "test connection" quota call (specs/06 §3.4). */
data class ConnectionTestResult(
    val accountId: String,
    val ok: Boolean,
    val errorCode: String?,
    val testedAt: Long,
)

/**
 * Diagnostics data + actions (specs/06 §3): per-account health/quota/
 * reservations, the transfer-job queue, recent failures, the "sweep stuck
 * jobs" maintenance and the "test connection" quota probe. Pure engine layer —
 * the screen only renders.
 */
class DiagnosticsService(
    private val db: CloudNestDatabase,
    private val health: HealthTracker,
    private val connections: AccountConnectionManager? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun accountSummaries(): List<AccountDiagnostics> =
        db.providerAccountDao().getAll().map { account ->
            val failures = health.recentFailures(account.id, limit = RECENT_FAILURE_WINDOW)
            AccountDiagnostics(
                account = account,
                verdict = health.verdict(account.id),
                lastErrorCode = failures.firstOrNull()?.errorCode,
                reservedBytes = db.reservationDao().sumBytesByAccount(account.id),
                recentFailureCount = failures.size,
            )
        }

    /** Queued/running/paused + recently finished jobs, newest first (§3.2). */
    suspend fun jobs(limit: Int = JOB_LIMIT): List<TransferJobEntity> =
        db.transferJobDao().listRecent(limit)

    /** Recent failed chunk attempts: which chunk, which reason (§3.3). */
    suspend fun recentFailures(limit: Int = FAILURE_LIMIT): List<FailureRow> =
        db.uploadAttemptDao().recentFailureRows(limit)

    /**
     * "আটকে থাকা জব ঝাড়ু" (§3.2): orphaned reservations go away and a job
     * claiming `running` with nobody driving it goes back to queued. Runs under
     * the transfer gate without marking a heavy transfer — a live drain simply
     * makes this wait (the gate proves no run is driving), never fights it.
     */
    suspend fun sweepStuckJobs(): Int {
        return TransferGate.own(heavy = false) {
            val cleaned = db.reservationDao().deleteForInactiveJobs() + db.reservationDao().deleteOrphaned()
            db.transferJobDao().listByStatus(JobStatus.RUNNING).forEach { job ->
                db.transferJobDao().update(
                    job.copy(status = JobStatus.QUEUED, updatedAt = clock()),
                )
            }
            cleaned
        }
    }

    /**
     * "টেস্ট কানেকশন" (§3.4): a small quota call per account. The connection
     * manager refreshes tokens, records the health outcome and updates the
     * stored quota through its non-swallowing probe; this only surfaces the
     * result for the screen.
     */
    suspend fun testConnection(accountId: String): ConnectionTestResult {
        val manager = connections
            ?: return ConnectionTestResult(accountId, ok = false, errorCode = "unavailable", testedAt = clock())
        return try {
            manager.probeQuota(accountId)
            ConnectionTestResult(accountId, ok = true, errorCode = null, testedAt = clock())
        } catch (e: ProviderException) {
            ConnectionTestResult(accountId, ok = false, errorCode = e.code, testedAt = clock())
        } catch (e: IllegalStateException) {
            ConnectionTestResult(accountId, ok = false, errorCode = "unknown", testedAt = clock())
        }
    }

    companion object {
        const val RECENT_FAILURE_WINDOW = 20
        const val JOB_LIMIT = 30
        const val FAILURE_LIMIT = 20
    }
}
