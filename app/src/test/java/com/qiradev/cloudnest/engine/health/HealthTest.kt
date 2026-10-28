package com.qiradev.cloudnest.engine.health

import com.qiradev.cloudnest.data.buildTestDb
import com.qiradev.cloudnest.data.entity.JobStatus
import com.qiradev.cloudnest.data.entity.PlacementStatus
import com.qiradev.cloudnest.data.testAccount
import com.qiradev.cloudnest.data.testChunk
import com.qiradev.cloudnest.data.testFile
import com.qiradev.cloudnest.data.testFolder
import com.qiradev.cloudnest.data.testPlacement
import com.qiradev.cloudnest.data.testUploadAttempt
import com.qiradev.cloudnest.engine.download.ChunkFetcher
import com.qiradev.cloudnest.engine.providers.AccountConnectionManager
import com.qiradev.cloudnest.engine.providers.FakeConnector
import com.qiradev.cloudnest.engine.providers.OAuthStateStore
import com.qiradev.cloudnest.engine.providers.ProviderErrorCode
import com.qiradev.cloudnest.engine.providers.ProviderException
import com.qiradev.cloudnest.engine.providers.TokenCipher
import com.qiradev.cloudnest.engine.providers.TokenPair
import com.qiradev.cloudnest.engine.providers.TokenStore
import com.qiradev.cloudnest.engine.upload.Hashing
import com.qiradev.cloudnest.engine.upload.InMemoryFileSource
import com.qiradev.cloudnest.engine.upload.UploadErrorCode
import com.qiradev.cloudnest.engine.upload.UploadService
import com.qiradev.cloudnest.engine.upload.UploadTestHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** specs/06 §2.2 — health verdict rules and the upload_attempts replay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HealthTest {

    private var now = 1_000_000_000_000L
    private val tracker = HealthTracker(clock = { now })

    private fun successes(n: Int, kind: String = "call") {
        repeat(n) { tracker.recordSuccess("acc", kind) }
    }

    private fun failures(n: Int, code: String = "provider_down") {
        repeat(n) { tracker.recordFailure("acc", code) }
    }

    @Test
    fun noWorkMeansHealthy() {
        assertEquals(Health.HEALTHY, tracker.verdict("acc"))
    }

    @Test
    fun lowFailureRateIsHealthy() {
        successes(19)
        failures(1)
        assertEquals(Health.HEALTHY, tracker.verdict("acc"))
    }

    @Test
    fun failureRateTenToFiftyPercentIsDegraded() {
        successes(7)
        failures(3) // 30%
        assertEquals(Health.DEGRADED, tracker.verdict("acc"))
    }

    @Test
    fun threeConsecutiveFailuresIsDegraded() {
        successes(9)
        failures(3)
        assertEquals(Health.DEGRADED, tracker.verdict("acc"))
    }

    @Test
    fun failureRateOverFiftyPercentIsDown() {
        successes(4)
        failures(6) // 60%
        assertEquals(Health.DOWN, tracker.verdict("acc"))
    }

    @Test
    fun tenConsecutiveFailuresIsDown() {
        successes(10)
        failures(10)
        assertEquals(Health.DOWN, tracker.verdict("acc"))
    }

    @Test
    fun zeroSuccessesWithWorkIsDown() {
        failures(2) // small streak would be degraded, but no success at all → down
        assertEquals(Health.DOWN, tracker.verdict("acc"))
    }

    @Test
    fun eventsOutsideTheWindowAreForgotten() {
        successes(10)
        failures(10)
        now += 25L * 60 * 60 * 1000
        assertEquals(Health.HEALTHY, tracker.verdict("acc"))
    }

    @Test
    fun recoveryAfterFailuresIsHealthyAgain() {
        failures(5)
        successes(95) // dilutes the window below 10%
        assertEquals(Health.HEALTHY, tracker.verdict("acc"))
    }

    @Test
    fun accountsAreIndependent() {
        failures(6)
        assertEquals(Health.DOWN, tracker.verdict("acc"))
        assertEquals(Health.HEALTHY, tracker.verdict("acc-other"))
    }

    @Test
    fun hydrateReplaysUploadAttempts() = runBlocking {
        val db = buildTestDb()
        db.providerAccountDao().insert(testAccount(id = "acc-1"))
        db.folderDao().insert(testFolder(id = "folder-1"))
        db.fileDao().insert(testFile())
        db.fileChunkDao().insert(testChunk())
        db.chunkPlacementDao().insert(testPlacement(id = "pl-1", chunkId = "chunk-1", accountId = "acc-1"))
        db.uploadAttemptDao().insert(
            testUploadAttempt(id = "a1", placementId = "pl-1", result = "ok", finishedAt = now),
        )
        db.uploadAttemptDao().insert(
            testUploadAttempt(id = "a2", placementId = "pl-1", attemptNo = 2, result = "error", finishedAt = now + 1),
        )

        val replay = HealthTracker(clock = { now + 2 })
        replay.hydrateFromDb(db)

        // 1 ok + 1 error → 50% → degraded (not over 50%, streak 1).
        assertEquals(Health.DEGRADED, replay.verdict("acc-1"))
    }

    @Test
    fun recentFailuresCarryErrorCodes() {
        tracker.recordSuccess("acc")
        tracker.recordFailure("acc", "quota_stale")
        val failures = tracker.recentFailures("acc", 5)
        assertEquals(1, failures.size)
        assertEquals("quota_stale", failures.first().errorCode)
    }

    /** specs/06 §2.2 impact: a Down account receives no new uploads. */
    @Test
    fun downAccountIsExcludedFromUploadRouting() = runBlocking {
        val db = buildTestDb()
        val harness = UploadTestHarness(db)
        harness.addAccount("acc-1", freeBytes = 10 * 1024 * 1024)
        val health = HealthTracker()
        repeat(10) { health.recordFailure("acc-1", "provider_down") }
        val service = UploadService(
            db = db,
            connectors = mapOf("google_drive" to FakeConnector()),
            tokens = { TokenPair("t", null, now + 3_600_000) },
            health = health,
        )
        val jobId = service.enqueue(
            InMemoryFileSource(ByteArray(1_000)),
            "folder-1",
            "a.bin",
            "application/octet-stream",
        )
        service.runJob(jobId)
        val job = db.transferJobDao().getById(jobId)!!
        assertEquals(JobStatus.FAILED, job.status)
        assertEquals(UploadErrorCode.NO_FREE_SPACE, job.errorCode)
    }

    /** The same setup with a healthy ledger uploads normally. */
    @Test
    fun healthyAccountStillReceivesUploads() = runBlocking {
        val db = buildTestDb()
        val harness = UploadTestHarness(db)
        harness.addAccount("acc-1", freeBytes = 10 * 1024 * 1024)
        val service = UploadService(
            db = db,
            connectors = mapOf("google_drive" to harness.connector),
            tokens = { TokenPair("t", null, now + 3_600_000) },
            health = HealthTracker(),
        )
        val jobId = service.enqueue(
            InMemoryFileSource(ByteArray(1_000)),
            "folder-1",
            "a.bin",
            "application/octet-stream",
        )
        service.runJob(jobId)
        assertEquals(JobStatus.DONE, db.transferJobDao().getById(jobId)!!.status)
    }

    /** specs/06 §2.1: download outcomes land in the ledger with the error code. */
    @Test
    fun downloadOutcomesAreRecorded() = runBlocking {
        val db = buildTestDb()
        val connector = FakeConnector()
        val bytes = ByteArray(64) { it.toByte() }
        db.providerAccountDao().insert(testAccount(id = "acc-1"))
        db.folderDao().insert(testFolder(id = "folder-1"))
        db.fileDao().insert(testFile())
        db.fileChunkDao().insert(
            testChunk().copy(sizeBytes = bytes.size.toLong(), sha256 = Hashing.hex(Hashing.sha256(bytes))),
        )
        db.chunkPlacementDao().insert(
            testPlacement(chunkId = "chunk-1", accountId = "acc-1")
                .copy(remoteId = "remote-1", status = PlacementStatus.VERIFIED),
        )
        connector.chunks["1"] = bytes
        val tracker = HealthTracker(clock = { now })
        val fetcher = ChunkFetcher(
            db = db,
            connectors = mapOf("google_drive" to connector),
            tokens = { TokenPair("t", null, now + 3_600_000) },
            delayFn = { },
            health = tracker,
        )
        val chunk = db.fileChunkDao().listByFile("file-1").first()

        // A dead copy → recorded failure; the same copy healthy again → recorded success.
        connector.failingRemoteIds.add("remote-1")
        runCatching { fetcher.fetch(chunk) }
        connector.failingRemoteIds.clear()
        fetcher.fetch(chunk)

        val failures = tracker.recentFailures("acc-1", 5)
        assertEquals(1, failures.size)
        assertEquals("download", failures.first().kind)
        assertEquals(Health.DEGRADED, tracker.verdict("acc-1")) // 1 failure + 1 success = 50%
    }

    /** specs/06 §2.1: quota-call outcomes (connect-time and refresh) land in the ledger. */
    @Test
    fun quotaOutcomesAreRecorded() = runBlocking {
        val db = buildTestDb()
        val connector = FakeConnector()
        connector.readQuotaException = ProviderException(ProviderErrorCode.PROVIDER_DOWN)
        val tracker = HealthTracker(clock = { now })
        val stateStore = OAuthStateStore { now }
        val manager = AccountConnectionManager(
            db = db,
            tokenStore = TokenStore(staticTestCipher()),
            connectors = mapOf("google_drive" to connector),
            stateStore = stateStore,
            health = tracker,
            clock = { now },
        )

        // Connect-time quota observation fails → failure recorded, call throws.
        val state = stateStore.newOauthState("google_drive")
        runCatching { manager.completeConnection("code", state) }
        val accountId = db.providerAccountDao().getAll().single().id
        assertEquals(Health.DOWN, tracker.verdict(accountId)) // only a failure so far

        // Refresh quota with the provider healthy again → success recorded.
        connector.readQuotaException = null
        manager.refreshQuota(accountId)
        assertEquals(Health.DEGRADED, tracker.verdict(accountId)) // 1 failure + 1 success = 50%
    }

    /** Real AES-256-GCM with a test-held key — AndroidKeyStore is not on the JVM. */
    private fun staticTestCipher(): TokenCipher {
        val keyBytes = "cloudnest-test-key-0123456789abc".toByteArray(Charsets.UTF_8)
        val key = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
        return object : TokenCipher {
            override fun encrypt(plain: ByteArray): ByteArray {
                val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
                return byteArrayOf(1) + iv + cipher.doFinal(plain)
            }

            override fun decrypt(blob: ByteArray): ByteArray {
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    javax.crypto.Cipher.DECRYPT_MODE,
                    key,
                    javax.crypto.spec.GCMParameterSpec(128, blob.copyOfRange(1, 13)),
                )
                return cipher.doFinal(blob, 13, blob.size - 13)
            }
        }
    }
}
