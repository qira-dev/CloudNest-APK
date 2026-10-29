package com.qiradev.cloudnest.engine.diagnostics

import com.qiradev.cloudnest.data.buildTestDb
import com.qiradev.cloudnest.data.entity.JobStatus
import com.qiradev.cloudnest.data.entity.ProviderAccountStatus
import com.qiradev.cloudnest.data.entity.QuotaStatus
import com.qiradev.cloudnest.data.entity.UploadAttemptResult
import com.qiradev.cloudnest.data.testAccount
import com.qiradev.cloudnest.data.testChunk
import com.qiradev.cloudnest.data.testFile
import com.qiradev.cloudnest.data.testFolder
import com.qiradev.cloudnest.data.testJob
import com.qiradev.cloudnest.data.testPlacement
import com.qiradev.cloudnest.data.testReservation
import com.qiradev.cloudnest.data.testUploadAttempt
import com.qiradev.cloudnest.engine.health.Health
import com.qiradev.cloudnest.engine.health.HealthTracker
import com.qiradev.cloudnest.engine.providers.AccountConnectionManager
import com.qiradev.cloudnest.engine.providers.FakeConnector
import com.qiradev.cloudnest.engine.providers.OAuthStateStore
import com.qiradev.cloudnest.engine.providers.ProviderErrorCode
import com.qiradev.cloudnest.engine.providers.ProviderException
import com.qiradev.cloudnest.engine.providers.TestTokenCipher
import com.qiradev.cloudnest.engine.providers.TokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * specs/06 §3 — diagnostics data + actions on the fake connector: per-account
 * summaries, stuck-job sweep, recent failures and the "test connection" probe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsTest {

    private lateinit var db: com.qiradev.cloudnest.data.CloudNestDatabase
    private lateinit var connector: FakeConnector
    private lateinit var tracker: HealthTracker
    private lateinit var service: DiagnosticsService
    private lateinit var tokens: TokenStore

    private var nowMs: Long = 1_000_000L

    @Before
    fun setUp(): Unit = runBlocking {
        nowMs = 1_000_000L
        db = buildTestDb()
        connector = FakeConnector()
        tracker = HealthTracker(clock = { nowMs })
        tokens = TokenStore(TestTokenCipher())
        val manager = AccountConnectionManager(
            db = db,
            tokenStore = tokens,
            connectors = mapOf(connector.id to connector),
            stateStore = OAuthStateStore { nowMs },
            health = tracker,
            clock = { nowMs },
        )
        service = DiagnosticsService(db, tracker, manager, clock = { nowMs })
    }

    private suspend fun seedAccount(id: String = "acc-1") {
        db.providerAccountDao().insert(testAccount(id = id))
    }

    /** A live job row so a reservation satisfies its FK (job_id → transfer_jobs). */
    private suspend fun seedJob(id: String, status: String) {
        db.folderDao().insert(testFolder(id = "folder-$id"))
        db.fileDao().insert(testFile(id = "file-$id", folderId = "folder-$id"))
        db.transferJobDao().insert(testJob(id = id, fileId = "file-$id").copy(status = status))
    }

    @Test
    fun summariesCarryVerdictQuotaAndReservations(): Unit = runBlocking {
        seedAccount()
        seedJob(id = "job-1", status = JobStatus.RUNNING)
        db.reservationDao().insert(testReservation(id = "res-1", accountId = "acc-1", bytes = 700L))
        // Degraded: three consecutive failures (specs/06 §2.2).
        repeat(3) { tracker.recordFailure("acc-1", "provider_down", kind = "upload") }

        val summaries = service.accountSummaries()
        assertEquals(1, summaries.size)
        val summary = summaries.single()
        // Three failures, zero successes in the window → Down (specs/06 §2.2).
        assertEquals(Health.DOWN, summary.verdict)
        assertEquals("provider_down", summary.lastErrorCode)
        assertEquals(700L, summary.reservedBytes)
        assertTrue(summary.recentFailureCount >= 3)
    }

    @Test
    fun threeConsecutiveFailuresWithBalancedRateStayDegraded(): Unit = runBlocking {
        seedAccount()
        // 3 successes then 3 failures: streak = 3 (→ Degraded), rate = 0.5 (not > 0.5).
        repeat(3) { tracker.recordSuccess("acc-1", kind = "upload") }
        repeat(3) { tracker.recordFailure("acc-1", "provider_down", kind = "upload") }
        assertEquals(Health.DEGRADED, service.accountSummaries().single().verdict)
    }

    @Test
    fun healthyWhenNoWorkHappened(): Unit = runBlocking {
        seedAccount()
        assertEquals(Health.HEALTHY, service.accountSummaries().single().verdict)
    }

    @Test
    fun sweepRequeuesStuckJobsAndClearsOrphanReservations(): Unit = runBlocking {
        seedAccount()
        // A live job with its reservation + a dead job whose reservation is orphaned.
        seedJob(id = "job-live", status = JobStatus.RUNNING)
        seedJob(id = "job-dead", status = JobStatus.DONE)
        db.reservationDao().insert(testReservation(id = "res-live", jobId = "job-live"))
        db.reservationDao().insert(testReservation(id = "res-dead", jobId = "job-dead"))

        val cleaned = service.sweepStuckJobs()

        assertEquals(1, cleaned)
        assertEquals(JobStatus.QUEUED, db.transferJobDao().getById("job-live")!!.status)
        // The inactive job's reservation is gone; the requeued job keeps its space.
        assertEquals(null, db.reservationDao().getAll().firstOrNull { it.id == "res-dead" })
        assertTrue(db.reservationDao().getAll().any { it.id == "res-live" })
    }

    @Test
    fun recentFailuresShowWhichChunkAndWhy(): Unit = runBlocking {
        seedAccount()
        db.folderDao().insert(testFolder())
        db.fileDao().insert(testFile())
        db.fileChunkDao().insert(testChunk(id = "chunk-1", fileId = "file-1", index = 0))
        db.chunkPlacementDao().insert(testPlacement(id = "pl-1", chunkId = "chunk-1", accountId = "acc-1"))
        db.uploadAttemptDao().insert(
            testUploadAttempt(id = "att-1", placementId = "pl-1", result = UploadAttemptResult.ERROR)
                .copy(errorCode = "chunk_hash_mismatch", finishedAt = nowMs),
        )

        val failures = service.recentFailures(10)
        assertEquals(1, failures.size)
        val row = failures.single()
        assertEquals("a.mp4", row.fileName)
        assertEquals(0, row.chunkIndex)
        assertEquals("chunk_hash_mismatch", row.errorCode)
    }

    @Test
    fun testConnectionSucceedsAndRefreshesQuota(): Unit = runBlocking {
        seedAccount(id = "acc-live")
        val encrypted = tokens.serialize("access-1", "refresh-1", expiresAtMs = nowMs + 60 * 60 * 1000)
        db.providerAccountDao().update(
            testAccount(id = "acc-live").copy(encryptedTokens = encrypted, tokenExpiresAt = nowMs + 60 * 60 * 1000),
        )

        val result = service.testConnection("acc-live")

        assertTrue(result.ok)
        val stored = db.providerAccountDao().getById("acc-live")!!
        assertEquals(QuotaStatus.CURRENT, stored.quotaStatus)
        assertEquals(1000L, stored.quotaTotalBytes)
        // The probe counts as quota work in the health ledger (specs/06 §2.1).
        assertEquals(Health.HEALTHY, tracker.verdict("acc-live"))
    }

    @Test
    fun testConnectionSurfacesTheFailureCode(): Unit = runBlocking {
        seedAccount(id = "acc-down")
        val encrypted = tokens.serialize("access-2", "refresh-2", expiresAtMs = nowMs + 60 * 60 * 1000)
        db.providerAccountDao().update(
            testAccount(id = "acc-down").copy(encryptedTokens = encrypted, tokenExpiresAt = nowMs + 60 * 60 * 1000),
        )
        connector.readQuotaException = ProviderException(ProviderErrorCode.PROVIDER_DOWN)

        val result = service.testConnection("acc-down")

        assertFalse(result.ok)
        assertEquals("provider_down", result.errorCode)
        // Account stays connected — only the health ledger drops (specs/04 §5).
        assertEquals(ProviderAccountStatus.CONNECTED, db.providerAccountDao().getById("acc-down")!!.status)
    }
}
