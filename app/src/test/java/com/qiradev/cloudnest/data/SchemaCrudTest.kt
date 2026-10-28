package com.qiradev.cloudnest.data

import com.qiradev.cloudnest.data.entity.FileStatus
import com.qiradev.cloudnest.data.entity.JobKind
import com.qiradev.cloudnest.data.entity.JobStatus
import com.qiradev.cloudnest.data.entity.MetaEntity
import com.qiradev.cloudnest.data.entity.PlacementStatus
import com.qiradev.cloudnest.data.entity.Providers
import com.qiradev.cloudnest.data.entity.QuotaStatus
import com.qiradev.cloudnest.data.entity.SettingsEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** specs/10 SchemaTest — CRUD for every table of specs/01 plus key DAO queries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SchemaCrudTest {

    private lateinit var db: CloudNestDatabase

    @Before
    fun setUp() {
        db = buildTestDb()
    }

    @Test
    fun metaTableRoundTrip() = runBlocking {
        db.metaDao().upsert(MetaEntity("schema_version", "1"))
        assertEquals("1", db.metaDao().getValue("schema_version"))
        db.metaDao().upsert(MetaEntity("schema_version", "2"))
        assertEquals("2", db.metaDao().getValue("schema_version"))
        db.metaDao().delete("schema_version")
        assertNull(db.metaDao().getValue("schema_version"))
    }

    @Test
    fun settingsTableRoundTrip() = runBlocking {
        db.settingsDao().upsert(SettingsEntity(SettingsKeys.WIFI_ONLY_UPLOAD, "false"))
        db.settingsDao().upsert(SettingsEntity(SettingsKeys.CHUNK_SIZE_MIB, "16"))
        assertEquals("false", db.settingsDao().getValue(SettingsKeys.WIFI_ONLY_UPLOAD))
        assertEquals(2, db.settingsDao().getAll().size)
        db.settingsDao().upsert(SettingsEntity(SettingsKeys.WIFI_ONLY_UPLOAD, "true"))
        assertEquals("true", db.settingsDao().getValue(SettingsKeys.WIFI_ONLY_UPLOAD))
        db.settingsDao().delete(SettingsKeys.WIFI_ONLY_UPLOAD)
        assertNull(db.settingsDao().getValue(SettingsKeys.WIFI_ONLY_UPLOAD))
    }

    @Test
    fun providerAccountTableRoundTrip() = runBlocking {
        val acc = testAccount()
        db.providerAccountDao().insert(acc)
        val loaded = db.providerAccountDao().getById(acc.id)
        assertEquals("Test drive", loaded?.label)
        assertEquals(Providers.GOOGLE_DRIVE, loaded?.provider)
        assertEquals("root-folder-id", loaded?.rootPath)

        db.providerAccountDao().insert(testAccount(id = "acc-2"))
        assertEquals(2, db.providerAccountDao().getAll().size)

        db.providerAccountDao().updateQuota("acc-1", 500L, 50L, QuotaStatus.STALE, 9_000L)
        val refreshed = db.providerAccountDao().getById("acc-1")
        assertEquals(500L, refreshed?.quotaTotalBytes)
        assertEquals(50L, refreshed?.quotaUsedBytes)
        assertEquals(QuotaStatus.STALE, refreshed?.quotaStatus)
        assertEquals(9_000L, refreshed?.quotaObservedAt)

        db.providerAccountDao().delete("acc-2")
        assertNull(db.providerAccountDao().getById("acc-2"))
    }

    @Test
    fun folderTableRoundTrip() = runBlocking {
        db.folderDao().insert(testFolder(id = "root", parent = null, name = ""))
        db.folderDao().insert(testFolder(id = "child-1", parent = "root", name = "b"))
        db.folderDao().insert(testFolder(id = "child-2", parent = "root", name = "a"))

        // parent_id IS NULL matches the root folder itself.
        assertEquals(listOf("root"), db.folderDao().listChildren(null).map { it.id })
        assertEquals(listOf("child-2", "child-1"), db.folderDao().listChildren("root").map { it.id })

        db.folderDao().update(testFolder(id = "child-2", parent = "root", name = "a").copy(deletedAt = 5_000L))
        assertEquals(listOf("child-1"), db.folderDao().listChildren("root").map { it.id })

        assertEquals("b", db.folderDao().getById("child-1")?.name)
        db.folderDao().delete("child-1")
        assertNull(db.folderDao().getById("child-1"))
    }

    @Test
    fun fileTableRoundTrip() = runBlocking {
        db.folderDao().insert(testFolder())
        db.fileDao().insert(testFile(id = "f-1", name = "a.mp4"))
        db.fileDao().insert(testFile(id = "f-2", name = "b.mp4"))

        assertEquals(1, db.fileDao().countByNameInFolder("folder-1", "a.mp4"))
        assertEquals(0, db.fileDao().countByNameInFolder("folder-1", "c.mp4"))

        db.fileDao().update(testFile(id = "f-1").copy(deletedAt = 9_000L))
        assertEquals(listOf("f-2"), db.fileDao().listByFolder("folder-1").map { it.id })

        val loaded = db.fileDao().getById("f-1")
        assertEquals(FileStatus.UPLOADING, loaded?.status)
        assertEquals(9_000L, loaded?.deletedAt)

        db.fileDao().delete("f-1")
        assertNull(db.fileDao().getById("f-1"))
    }

    @Test
    fun fileChunkTableRoundTrip() = runBlocking {
        db.folderDao().insert(testFolder())
        db.fileDao().insert(testFile())
        db.fileChunkDao().insert(testChunk(id = "c-1", index = 1))
        db.fileChunkDao().insert(testChunk(id = "c-0", index = 0))

        assertEquals(listOf("c-0", "c-1"), db.fileChunkDao().listByFile("file-1").map { it.id })
        assertEquals(2, db.fileChunkDao().countByFile("file-1"))
        assertEquals(0, db.fileChunkDao().getById("c-0")?.chunkIndex)

        db.fileChunkDao().delete("c-0")
        assertNull(db.fileChunkDao().getById("c-0"))
    }

    @Test
    fun chunkPlacementTableRoundTrip() = runBlocking {
        seedPlacementChain()
        db.providerAccountDao().insert(testAccount(id = "acc-2"))
        db.chunkPlacementDao().insert(testPlacement(id = "pl-2", chunkId = "chunk-1", accountId = "acc-2"))

        assertEquals(listOf("pl-1", "pl-2"), db.chunkPlacementDao().listByChunk("chunk-1").map { it.id })

        db.chunkPlacementDao().update(
            testPlacement(id = "pl-1").copy(status = PlacementStatus.VERIFIED, remoteId = "remote-1", uploadedAt = 8_000L),
        )
        val verified = db.chunkPlacementDao().getById("pl-1")
        assertEquals(PlacementStatus.VERIFIED, verified?.status)
        assertEquals("remote-1", verified?.remoteId)

        assertEquals(
            listOf("pl-1"),
            db.chunkPlacementDao().listByAccountAndStatus("acc-1", PlacementStatus.VERIFIED).map { it.id },
        )
        db.chunkPlacementDao().delete("pl-2")
        assertNull(db.chunkPlacementDao().getById("pl-2"))
    }

    @Test
    fun reservationTableRoundTrip() = runBlocking {
        seedPlacementChain()
        db.transferJobDao().insert(testJob(id = "job-1"))
        db.reservationDao().insert(testReservation(id = "res-1", accountId = "acc-1", jobId = "job-1", bytes = 500L))
        assertEquals(500L, db.reservationDao().sumBytesByAccount("acc-1"))

        db.reservationDao().deleteByJob("job-1")
        assertEquals(0L, db.reservationDao().sumBytesByAccount("acc-1"))

        db.reservationDao().insert(testReservation(id = "res-2", accountId = "acc-1", jobId = "job-1", bytes = 300L))
        db.reservationDao().deleteById("res-2")
        assertTrue(db.reservationDao().getAll().isEmpty())
    }

    @Test
    fun transferJobTableRoundTrip() = runBlocking {
        db.folderDao().insert(testFolder())
        db.fileDao().insert(testFile())
        db.transferJobDao().insert(testJob(id = "job-b", kind = JobKind.UPLOAD, createdAt = 2_000L))
        db.transferJobDao().insert(testJob(id = "job-a", kind = JobKind.UPLOAD, createdAt = 1_000L))
        db.transferJobDao().insert(testJob(id = "job-d", kind = JobKind.DOWNLOAD, createdAt = 3_000L))

        assertEquals("job-a", db.transferJobDao().nextQueued(JobKind.UPLOAD)?.id)
        assertEquals("job-d", db.transferJobDao().nextQueued(JobKind.DOWNLOAD)?.id)

        db.transferJobDao().update(
            testJob(id = "job-a", kind = JobKind.UPLOAD).copy(status = JobStatus.DONE, attempts = 1, updatedAt = 5_000L),
        )
        assertEquals("job-b", db.transferJobDao().nextQueued(JobKind.UPLOAD)?.id)
        assertEquals(listOf("job-a"), db.transferJobDao().listByStatus(JobStatus.DONE).map { it.id })

        db.transferJobDao().delete("job-b")
        assertNull(db.transferJobDao().getById("job-b"))
    }

    @Test
    fun uploadAttemptTableRoundTrip() = runBlocking {
        seedPlacementChain()
        db.uploadAttemptDao().insert(testUploadAttempt(id = "att-old", attemptNo = 1, finishedAt = 1_000L))
        db.uploadAttemptDao().insert(testUploadAttempt(id = "att-new", attemptNo = 2, finishedAt = 99_000L))
        db.uploadAttemptDao().insert(testUploadAttempt(id = "att-live", attemptNo = 3, finishedAt = null, result = null))

        assertEquals(listOf(1, 2, 3), db.uploadAttemptDao().listByPlacement("pl-1").map { it.attemptNo })

        val deleted = db.uploadAttemptDao().deleteOlderThan(cutoff = 10_000L)
        assertEquals(1, deleted)
        assertEquals(
            setOf("att-new", "att-live"),
            db.uploadAttemptDao().listByPlacement("pl-1").map { it.id }.toSet(),
        )
    }

    @Test
    fun deletionAttemptTableRoundTrip() = runBlocking {
        db.folderDao().insert(testFolder())
        db.fileDao().insert(testFile())
        db.deletionAttemptDao().insert(testDeletionAttempt())
        val loaded = db.deletionAttemptDao().listByFile("file-1")
        assertEquals(listOf("del-1"), loaded.map { it.id })
        assertEquals("""["remote-1"]""", loaded.first().remoteIdsJson)

        db.deletionAttemptDao().deleteByFile("file-1")
        assertTrue(db.deletionAttemptDao().listByFile("file-1").isEmpty())
    }

    @Test
    fun notificationTableRoundTrip() = runBlocking {
        db.notificationDao().insert(testNotification(id = "n-1", createdAt = 1_000L))
        db.notificationDao().insert(testNotification(id = "n-2", createdAt = 2_000L))
        db.notificationDao().insert(testNotification(id = "n-3", createdAt = 3_000L, read = 1))

        assertEquals(listOf("n-3", "n-2"), db.notificationDao().latest(2).map { it.id })
        assertEquals(listOf("n-2", "n-1"), db.notificationDao().listUnread().map { it.id })

        db.notificationDao().markRead("n-2")
        assertEquals(listOf("n-1"), db.notificationDao().listUnread().map { it.id })

        assertEquals(1, db.notificationDao().trimTo(2))
        assertEquals(setOf("n-2", "n-3"), db.notificationDao().latest(100).map { it.id }.toSet())
    }

    @Test
    fun auditEventTableRoundTrip() = runBlocking {
        db.auditEventDao().insert(testAuditEvent(id = "a-1", createdAt = 1_000L))
        db.auditEventDao().insert(testAuditEvent(id = "a-2", createdAt = 2_000L))
        assertEquals(listOf("a-2", "a-1"), db.auditEventDao().latest(10).map { it.id })

        assertEquals(0, db.auditEventDao().trimTo(2))
        db.auditEventDao().insert(testAuditEvent(id = "a-3", createdAt = 3_000L))
        db.auditEventDao().insert(testAuditEvent(id = "a-4", createdAt = 4_000L))
        assertEquals(2, db.auditEventDao().trimTo(2))
        assertEquals(setOf("a-3", "a-4"), db.auditEventDao().latest(10).map { it.id }.toSet())
    }

    @Test
    fun databaseSeedsSchemaVersionIntoMeta() = runBlocking {
        assertEquals("1", db.metaDao().getValue("schema_version"))
    }

    private suspend fun seedPlacementChain() {
        db.providerAccountDao().insert(testAccount())
        db.folderDao().insert(testFolder())
        db.fileDao().insert(testFile())
        db.fileChunkDao().insert(testChunk())
        db.chunkPlacementDao().insert(testPlacement())
    }
}
