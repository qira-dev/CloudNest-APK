package com.qiradev.cloudnest.background

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.qiradev.cloudnest.data.buildTestDb
import com.qiradev.cloudnest.data.entity.FileStatus
import com.qiradev.cloudnest.data.entity.JobStatus
import com.qiradev.cloudnest.data.entity.NotificationType
import com.qiradev.cloudnest.data.entity.PlacementStatus
import com.qiradev.cloudnest.data.entity.ProviderAccountStatus
import com.qiradev.cloudnest.data.entity.QuotaStatus
import com.qiradev.cloudnest.engine.archive.ArchiveService
import com.qiradev.cloudnest.engine.mirror.MirrorService
import com.qiradev.cloudnest.engine.providers.AccountConnectionManager
import com.qiradev.cloudnest.engine.providers.FakeConnector
import com.qiradev.cloudnest.engine.providers.OAuthStateStore
import com.qiradev.cloudnest.engine.providers.ProviderErrorCode
import com.qiradev.cloudnest.engine.providers.ProviderException
import com.qiradev.cloudnest.engine.providers.Quota
import com.qiradev.cloudnest.engine.providers.TokenCipher
import com.qiradev.cloudnest.engine.providers.TokenPair
import com.qiradev.cloudnest.engine.providers.TokenStore
import com.qiradev.cloudnest.engine.upload.UploadService
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * specs/05 §1 + specs/10 — the recurring workers, driven with the WorkManager
 * test builder; every dependency is injected so no production singleton runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
abstract class WorkerTestBase {

    protected lateinit var db: com.qiradev.cloudnest.data.CloudNestDatabase

    @Before
    open fun setUp() {
        db = buildTestDb()
    }

    protected inline fun <reified W : ListenableWorker> buildWorker(
        noinline create: (android.content.Context, WorkerParameters) -> W,
    ): W = TestListenableWorkerBuilder<W>(ApplicationProvider.getApplicationContext())
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(
                appContext: android.content.Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? = create(appContext, workerParameters)
        })
        .build()

    protected fun testTokenStore() = TokenStore(staticTestCipher())

    /** Real AES-256-GCM with a test-held key — AndroidKeyStore is not on the JVM. */
    protected fun staticTestCipher(): TokenCipher {
        val key = SecretKeySpec("cloudnest-test-key-0123456789abc".toByteArray(Charsets.UTF_8), "AES")
        return object : TokenCipher {
            override fun encrypt(plain: ByteArray): ByteArray {
                val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
                return byteArrayOf(1) + iv + cipher.doFinal(plain)
            }

            override fun decrypt(blob: ByteArray): ByteArray {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(1, 13)))
                return cipher.doFinal(blob, 13, blob.size - 13)
            }
        }
    }

    /** A connected account produced by the real connect flow (tokens stored encrypted). */
    protected suspend fun connectAccount(
        connector: FakeConnector,
        health: com.qiradev.cloudnest.engine.health.HealthTracker? = null,
    ): String {
        val stateStore = OAuthStateStore { System.currentTimeMillis() }
        val manager = AccountConnectionManager(
            db = db,
            tokenStore = testTokenStore(),
            connectors = mapOf(connector.id to connector),
            stateStore = stateStore,
            health = health,
        )
        val state = stateStore.newOauthState("google_drive")
        return manager.completeConnection("code", state)
    }

    protected fun archiveFor(connector: FakeConnector) = ArchiveService(
        db,
        mapOf("google_drive" to connector),
        { TokenPair("t", null, Long.MAX_VALUE) },
    )

    protected fun uploadFor(connector: FakeConnector) = UploadService(
        db = db,
        connectors = mapOf("google_drive" to connector),
        tokens = { TokenPair("t", null, Long.MAX_VALUE) },
    )

    protected fun mirrorFor(connector: FakeConnector) = MirrorService(
        db = db,
        connectors = mapOf("google_drive" to connector),
        tokens = { TokenPair("t", null, Long.MAX_VALUE) },
    )
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuotaRefreshWorkerTest : WorkerTestBase() {

    private fun worker(connector: FakeConnector): QuotaRefreshWorker {
        val stateStore = OAuthStateStore { System.currentTimeMillis() }
        val manager = AccountConnectionManager(
            db = db,
            tokenStore = testTokenStore(),
            connectors = mapOf(connector.id to connector),
            stateStore = stateStore,
        )
        return buildWorker { context, params -> QuotaRefreshWorker(context, params, db, manager) }
    }

    @Test
    fun refreshesQuotaOfEveryConnectedAccount() = runBlocking {
        val connector = FakeConnector()
        connector.quota = Quota(500L, 40L, false)
        val accountId = connectAccount(connector)

        val result = worker(connector).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val account = db.providerAccountDao().getById(accountId)!!
        assertEquals(QuotaStatus.CURRENT, account.quotaStatus)
        assertEquals(500L, account.quotaTotalBytes)
        assertEquals(40L, account.quotaUsedBytes)
    }

    @Test
    fun threeDaysWithoutSuccessfulObservationTurnsStaleWithNotice() = runBlocking {
        val connector = FakeConnector()
        val accountId = connectAccount(connector)
        // The last successful observation is 4 days old and the provider is
        // failing now — the refresh cannot lift the account, so it goes stale.
        connector.readQuotaException = ProviderException(ProviderErrorCode.PROVIDER_DOWN)
        val account = db.providerAccountDao().getById(accountId)!!
        db.providerAccountDao().update(
            account.copy(quotaObservedAt = System.currentTimeMillis() - 4L * 24 * 60 * 60 * 1000),
        )

        worker(connector).doWork()

        val staled = db.providerAccountDao().getById(accountId)!!
        assertEquals(QuotaStatus.STALE, staled.quotaStatus)
        assertEquals(NotificationType.QUOTA_STALE, db.notificationDao().latest(5).first().type)
        // Idempotent: a second run does not duplicate the notice.
        worker(connector).doWork()
        assertEquals(
            1,
            db.notificationDao().latest(10).count { it.type == NotificationType.QUOTA_STALE },
        )
    }

    @Test
    fun failingProviderKeepsAccountConnectedWithoutStaling() = runBlocking {
        val connector = FakeConnector()
        val accountId = connectAccount(connector)
        connector.readQuotaException = ProviderException(ProviderErrorCode.PROVIDER_DOWN)

        val result = worker(connector).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        // The observation is fresh (connect just succeeded), so the account is
        // `unknown` — not stale; it stays connected for routing to skip.
        val account = db.providerAccountDao().getById(accountId)!!
        assertEquals(ProviderAccountStatus.CONNECTED, account.status)
        assertEquals(QuotaStatus.UNKNOWN, account.quotaStatus)
        assertTrue(
            db.notificationDao().latest(5).none { it.type == NotificationType.QUOTA_STALE },
        )
    }

    @Test
    fun tokenExpiryWritesOneStickyNoticePerProvider() = runBlocking {
        val connector = FakeConnector()
        connectAccount(connector)
        connector.readQuotaException = ProviderException(ProviderErrorCode.AUTH_EXPIRED)

        worker(connector).doWork()

        // specs/05 §2.2: token expiry surfaces as a sign-in-required notice.
        val notice = db.notificationDao().latest(5).first { it.type == NotificationType.TOKEN_EXPIRED }
        assertEquals("google_drive", org.json.JSONObject(notice.payload).optString("provider"))
        // Idempotent: repeated runs do not stack the notice while it stays unread.
        worker(connector).doWork()
        assertEquals(
            1,
            db.notificationDao().latest(10).count { it.type == NotificationType.TOKEN_EXPIRED },
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrashCleanupWorkerTest : WorkerTestBase() {

    @Test
    fun purgesExpiredFilesThroughTheEngine() = runBlocking {
        val connector = FakeConnector()
        val accountId = connectAccount(connector)
        db.folderDao().insert(com.qiradev.cloudnest.data.testFolder(id = "folder-1", parent = null, name = ""))
        db.fileDao().insert(com.qiradev.cloudnest.data.testFile(id = "file-1").copy(deletedAt = 1L, purgeAt = 2L))
        db.fileChunkDao().insert(com.qiradev.cloudnest.data.testChunk(id = "chunk-1", fileId = "file-1"))
        val remoteId = "remote-purge-me"
        connector.chunks["purge-me"] = ByteArray(8)
        db.chunkPlacementDao().insert(
            com.qiradev.cloudnest.data.testPlacement(chunkId = "chunk-1", accountId = accountId)
                .copy(remoteId = remoteId, status = PlacementStatus.VERIFIED),
        )

        val result = buildWorker { context, params ->
            TrashCleanupWorker(context, params, db, archiveFor(connector))
        }.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertNull(db.fileDao().getById("file-1"))
        assertTrue(connector.deletedRemoteIds.contains(remoteId))
        assertEquals(NotificationType.TRASH_PURGED, db.notificationDao().latest(5).first().type)
    }

    @Test
    fun leavesFilesWhoseTimeHasNotCome() = runBlocking {
        val connector = FakeConnector()
        val accountId = connectAccount(connector)
        db.folderDao().insert(com.qiradev.cloudnest.data.testFolder(id = "folder-1", parent = null, name = ""))
        val future = System.currentTimeMillis() + 10L * 24 * 60 * 60 * 1000
        db.fileDao().insert(com.qiradev.cloudnest.data.testFile(id = "file-1").copy(deletedAt = 1L, purgeAt = future))
        db.fileChunkDao().insert(com.qiradev.cloudnest.data.testChunk(id = "chunk-1", fileId = "file-1"))
        db.chunkPlacementDao().insert(
            com.qiradev.cloudnest.data.testPlacement(chunkId = "chunk-1", accountId = accountId)
                .copy(remoteId = "remote-1", status = PlacementStatus.VERIFIED),
        )

        buildWorker { context, params ->
            TrashCleanupWorker(context, params, db, archiveFor(connector))
        }.doWork()

        assertTrue(db.fileDao().getById("file-1") != null)
        assertTrue(connector.deletedRemoteIds.isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JobReviveWorkerTest : WorkerTestBase() {

    @Test
    fun requeuesOrphansAndDrainsTheQueue() = runBlocking {
        val connector = FakeConnector()
        connector.quota = Quota(1_000_000L, 0L, false)
        connectAccount(connector)
        val enqueuer = uploadFor(connector)
        val rootId = archiveFor(connector).ensureRootFolder().id

        // A cached upload source: the "process" enqueued and died right after.
        val cache = File(CacheUploadSource.directory(ApplicationProvider.getApplicationContext()), "revived.bin")
        cache.parentFile?.mkdirs()
        cache.writeBytes(ByteArray(1_024) { (it % 251).toByte() })
        val jobId = enqueuer.enqueue(CacheUploadSource(cache), rootId, "revived.bin", "application/octet-stream")
        val fileId = db.transferJobDao().getById(jobId)!!.fileId!!
        UploadSourceStore.saveCache(db, fileId, cache)
        // The dead process left the job claiming `running`.
        db.transferJobDao().update(db.transferJobDao().getById(jobId)!!.copy(status = JobStatus.RUNNING))

        // A fresh service instance — the transient source registry is empty, as
        // after a process death; the driver must rebuild it from the store.
        val freshUpload = uploadFor(connector)
        assertFalse(freshUpload.hasSource(jobId))
        val result = buildWorker { context, params ->
            JobReviveWorker(context, params, db, freshUpload to mirrorFor(connector))
        }.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val job = db.transferJobDao().getById(jobId)!!
        assertEquals(JobStatus.DONE, job.status)
        assertEquals(FileStatus.COMPLETE, db.fileDao().getById(job.fileId!!)!!.status)
        assertNull(db.metaDao().getValue("upload_source:$fileId"))
        assertFalse(cache.exists())
    }

    @Test
    fun queueWithUnreadableSourceFailsHonestly() = runBlocking {
        val connector = FakeConnector()
        connectAccount(connector)
        val enqueuer = uploadFor(connector)
        val rootId = archiveFor(connector).ensureRootFolder().id
        val jobId = enqueuer.enqueue(
            CacheUploadSource(File("/nonexistent/revived.bin")),
            rootId,
            "revived.bin",
            "application/octet-stream",
        )
        UploadSourceStore.saveCache(db, db.transferJobDao().getById(jobId)!!.fileId!!, File("/nonexistent/revived.bin"))
        val freshUpload = uploadFor(connector)

        buildWorker { context, params ->
            JobReviveWorker(context, params, db, freshUpload to mirrorFor(connector))
        }.doWork()

        val job = db.transferJobDao().getById(jobId)!!
        assertEquals(JobStatus.FAILED, job.status)
        assertEquals(UploadDrivers.SOURCE_UNREADABLE, job.errorCode)
    }
}
