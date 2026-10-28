package com.qiradev.cloudnest.engine.archive

import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.SettingsKeys
import com.qiradev.cloudnest.data.buildTestDb
import com.qiradev.cloudnest.data.entity.FileStatus
import com.qiradev.cloudnest.data.entity.JobKind
import com.qiradev.cloudnest.data.entity.JobStatus
import com.qiradev.cloudnest.data.entity.NotificationType
import com.qiradev.cloudnest.data.entity.PlacementRole
import com.qiradev.cloudnest.data.entity.PlacementStatus
import com.qiradev.cloudnest.data.entity.ProviderAccountEntity
import com.qiradev.cloudnest.data.entity.ProviderAccountStatus
import com.qiradev.cloudnest.data.entity.QuotaStatus
import com.qiradev.cloudnest.data.entity.SettingsEntity
import com.qiradev.cloudnest.data.testChunk
import com.qiradev.cloudnest.data.testFile
import com.qiradev.cloudnest.data.testPlacement
import com.qiradev.cloudnest.engine.providers.FakeConnector
import com.qiradev.cloudnest.engine.providers.ProviderException
import com.qiradev.cloudnest.engine.providers.TokenPair
import com.qiradev.cloudnest.engine.upload.TokenProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * specs/10 — folder tree + trash behavior (specs/01 §1.4/§1.5/§1.11): cascade
 * trash, restore, due purge with the deletion ledger, empty-trash. All data
 * synthetic; cloud side is the FakeConnector.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchiveServiceTest {

    private lateinit var db: com.qiradev.cloudnest.data.CloudNestDatabase
    private lateinit var connector: FakeConnector
    private lateinit var service: ArchiveService

    /** Fixed clock start; advanced explicitly via [advance]. */
    private var now = 1_000_000_000_000L

    private fun advance(ms: Long) {
        now += ms
    }

    private val dayMs = 24L * 60 * 60 * 1000

    @Before
    fun setUp() = runBlocking {
        db = buildTestDb()
        connector = FakeConnector()
        service = ArchiveService(
            db = db,
            connectors = mapOf("google_drive" to connector),
            tokens = TokenProvider { TokenPair("test-token", null, now + 3_600_000) },
            clock = { now },
        )
        seedAccount()
    }

    private suspend fun seedAccount() {
        db.providerAccountDao().insert(
            ProviderAccountEntity(
                id = "acc-1",
                provider = "google_drive",
                label = "acc",
                status = ProviderAccountStatus.CONNECTED,
                encryptedTokens = null,
                tokenExpiresAt = 0,
                quotaTotalBytes = 1_000L,
                quotaUsedBytes = 0,
                quotaUnlimited = 0,
                quotaStatus = QuotaStatus.CURRENT,
                quotaObservedAt = now,
                quotaScopeKey = null,
                rootPath = "fake-root",
                createdAt = now,
                lastUsedAt = now,
            ),
        )
    }

    /** A complete file with one verified primary placement whose remote object exists. */
    private suspend fun seedCompletedFile(
        fileId: String = "file-1",
        folderId: String,
        remoteId: String = "remote-$fileId",
    ) {
        db.fileDao().insert(testFile(id = fileId, folderId = folderId).copy(status = FileStatus.COMPLETE))
        db.fileChunkDao().insert(testChunk(id = "chunk-$fileId", fileId = fileId))
        connector.chunks[remoteId] = ByteArray(8)
        db.chunkPlacementDao().insert(
            testPlacement(id = "pl-$fileId", chunkId = "chunk-$fileId", accountId = "acc-1").copy(
                role = PlacementRole.PRIMARY,
                remoteId = remoteId,
                status = PlacementStatus.VERIFIED,
                uploadedAt = now,
            ),
        )
    }

    private suspend fun seedTree(): Pair<String, String> {
        val root = service.ensureRootFolder()
        val child = service.createFolder(root.id, "docs")
        db.fileDao().insert(testFile(id = "file-1", folderId = child).copy(status = FileStatus.COMPLETE))
        return Pair(root.id, child)
    }

    // ---- folder tree ----

    @Test
    fun ensureRootFolderIsIdempotent() = runBlocking {
        val root1 = service.ensureRootFolder()
        val root2 = service.ensureRootFolder()
        assertEquals(root1.id, root2.id)
        assertNull(root2.parentId)
        assertEquals("", root2.name)
    }

    @Test
    fun breadcrumbsWalkRootFirst() = runBlocking {
        val root = service.ensureRootFolder()
        val child = service.createFolder(root.id, "docs")
        val grand = service.createFolder(child, "2026")
        val crumbs = service.breadcrumbs(grand)
        assertEquals(listOf(root.id, child, grand), crumbs.map { it.id })
    }

    @Test
    fun createFolderRejectsBlankNameAndTrashedParent() = runBlocking {
        val root = service.ensureRootFolder()
        val child = service.createFolder(root.id, "docs")
        service.trashFolder(child)
        var threwOnTrashedParent = false
        try {
            service.createFolder(child, "x")
        } catch (e: IllegalStateException) {
            threwOnTrashedParent = true
        }
        assertTrue(threwOnTrashedParent)
        var threwOnBlankName = false
        try {
            service.createFolder(root.id, "   ")
        } catch (e: IllegalArgumentException) {
            threwOnBlankName = true
        }
        assertTrue(threwOnBlankName)
    }

    // ---- trash ----

    @Test
    fun trashFileSetsDeletedAndPurgeAtWithDefaultSevenDays() = runBlocking {
        val (_, child) = seedTree()
        service.trashFile("file-1")
        val file = db.fileDao().getById("file-1")!!
        val trashAt = file.deletedAt!!
        assertEquals(trashAt + 7 * dayMs, file.purgeAt)
        assertEquals("file_deleted", db.auditEventDao().latest(10).first().type)
    }

    @Test
    fun trashFolderCascadesToSubtreeFoldersAndFiles() = runBlocking {
        val root = service.ensureRootFolder()
        val child = service.createFolder(root.id, "docs")
        val grand = service.createFolder(child, "2026")
        db.fileDao().insert(testFile(id = "file-1", folderId = grand))

        service.trashFolder(child)

        assertNotNull(db.folderDao().getById(child)!!.deletedAt)
        assertNotNull(db.folderDao().getById(grand)!!.deletedAt)
        assertNotNull(db.fileDao().getById("file-1")!!.deletedAt)
        assertEquals(
            db.folderDao().getById(child)!!.purgeAt,
            db.fileDao().getById("file-1")!!.purgeAt,
        )
    }

    @Test
    fun restoreFileAlsoRevivesTrashedAncestors() = runBlocking {
        val (_, child) = seedTree()
        service.trashFile("file-1")
        advance(1_000)
        service.trashFolder(child)
        advance(1_000)
        service.restoreFile("file-1")
        assertNull(db.folderDao().getById(child)!!.deletedAt)
        assertNull(db.fileDao().getById("file-1")!!.deletedAt)
        assertNull(db.fileDao().getById("file-1")!!.purgeAt)
        assertEquals("file_restored", db.auditEventDao().latest(10).first().type)
    }

    @Test
    fun restoreFolderRevivesWholeSubtree() = runBlocking {
        val root = service.ensureRootFolder()
        val child = service.createFolder(root.id, "docs")
        val grand = service.createFolder(child, "2026")
        db.fileDao().insert(testFile(id = "file-1", folderId = grand))
        service.trashFolder(child)

        service.restoreFolder(child)

        assertNull(db.folderDao().getById(grand)!!.deletedAt)
        assertNull(db.fileDao().getById("file-1")!!.deletedAt)
    }

    // ---- purge ----

    @Test
    fun purgeBeforeDueKeepsEverything() = runBlocking {
        val root = service.ensureRootFolder()
        seedCompletedFile(folderId = root.id)
        service.trashFile("file-1")

        service.purgeDue(now + 6 * dayMs)

        assertNotNull(db.fileDao().getById("file-1"))
        assertTrue(connector.deletedRemoteIds.isEmpty())
    }

    @Test
    fun purgeDueDeletesCloudObjectsLedgersAndRows() = runBlocking {
        val root = service.ensureRootFolder()
        seedCompletedFile(folderId = root.id)
        service.trashFile("file-1")

        val summary = service.purgeDue(now + 7 * dayMs + 1)

        assertEquals(1, summary.purgedFiles)
        assertEquals(1, summary.deletedRemoteObjects)
        assertEquals(listOf("remote-file-1"), connector.deletedRemoteIds)
        assertNull(db.fileDao().getById("file-1"))
        // Cascade: chunks and placements are gone with the file row.
        assertTrue(db.fileChunkDao().listByFile("file-1").isEmpty())
        assertTrue(db.chunkPlacementDao().listByFile("file-1").isEmpty())
        // Purge job ledger says done; the file FK nulls file_id when the row goes.
        val job = db.transferJobDao().listByKindAndStatus(JobKind.PURGE, JobStatus.DONE).single()
        assertNull(job.fileId)
        // The permanent record lives in the audit trail.
        val purgeAudit = db.auditEventDao().latest(10).first { it.type == "file_purged" }
        assertTrue(purgeAudit.detailJson.contains("file-1"))
        // trash_purged notification emitted.
        assertEquals(NotificationType.TRASH_PURGED, db.notificationDao().latest(10).first().type)
    }

    @Test
    fun purgeFailureKeepsFileForRetryAndLedgerRecordsError() = runBlocking {
        val root = service.ensureRootFolder()
        seedCompletedFile(folderId = root.id)
        service.trashFile("file-1")
        advance(7 * dayMs + 1)

        // Fail every delete during this pass.
        val failing = object : com.qiradev.cloudnest.engine.providers.ProviderConnector by connector {
            override suspend fun deleteObject(tokens: com.qiradev.cloudnest.engine.providers.TokenPair, remoteId: String) {
                throw ProviderException("provider_down")
            }
        }
        val failingService = ArchiveService(
            db = db,
            connectors = mapOf("google_drive" to failing),
            tokens = TokenProvider { TokenPair("t", null, now + 3_600_000) },
            clock = { now },
        )
        val summary = failingService.purgeDue(now)

        assertEquals(0, summary.purgedFiles)
        assertEquals(1, summary.failedFiles)
        assertNotNull(db.fileDao().getById("file-1"))
        val failedJob = db.transferJobDao().listByKindAndStatus(JobKind.PURGE, JobStatus.FAILED).single()
        assertEquals("provider_down", failedJob.errorCode)
        val ledger = db.deletionAttemptDao().listByFile("file-1").single()
        assertEquals("error", ledger.result)
        assertEquals("provider_down", ledger.errorCode)
    }

    @Test
    fun purgeRetryNeverDeletesTheSameRemoteTwice() = runBlocking {
        val root = service.ensureRootFolder()
        seedCompletedFile(folderId = root.id, remoteId = "remote-a")
        service.trashFile("file-1")
        advance(7 * dayMs + 1)

        // First pass: remote deletion succeeds mid-way but the pass reports failure
        // (simulate a crash after delete by deleting manually through the connector
        // and recording an ok ledger with the remote id, as the engine would).
        connector.deleteObject(TokenPair("t", null, now), "remote-a")
        db.deletionAttemptDao().insert(
            com.qiradev.cloudnest.data.entity.DeletionAttemptEntity(
                id = "prior-ok",
                fileId = "file-1",
                startedAt = now,
                finishedAt = now,
                result = "ok",
                errorCode = null,
                remoteIdsJson = """["remote-a"]""",
            ),
        )

        val summary = service.purgeDue(now)

        // The already-deleted remote id must not be deleted a second time.
        assertEquals(1, connector.deletedRemoteIds.count { it == "remote-a" })
        assertEquals(1, summary.purgedFiles)
        assertNull(db.fileDao().getById("file-1"))
    }

    @Test
    fun purgeFolderOnlyWhenSubtreeClearedAndBottomUp() = runBlocking {
        val root = service.ensureRootFolder()
        val child = service.createFolder(root.id, "docs")
        val grand = service.createFolder(child, "2026")
        seedCompletedFile(fileId = "file-1", folderId = grand)
        service.trashFolder(child)
        val due = now + 7 * dayMs + 1

        val summary = service.purgeDue(due)

        assertEquals(1, summary.purgedFiles)
        assertEquals(2, summary.purgedFolders) // grand before child — RESTRICT FK holds
        assertNull(db.folderDao().getById(child))
        assertNull(db.folderDao().getById(grand))
    }

    @Test
    fun emptyTrashPurgesEverythingImmediately() = runBlocking {
        val root = service.ensureRootFolder()
        seedCompletedFile(fileId = "file-1", folderId = root.id)
        seedCompletedFile(fileId = "file-2", folderId = root.id, remoteId = "remote-2")
        service.trashFile("file-1")
        service.trashFile("file-2")

        val summary = service.emptyTrash()

        assertEquals(2, summary.purgedFiles)
        assertNull(db.fileDao().getById("file-1"))
        assertNull(db.fileDao().getById("file-2"))
        assertTrue(db.folderDao().listTrashed().isEmpty())
    }

    // ---- settings + dedup ----

    @Test
    fun trashRetentionReadsSettings() = runBlocking {
        db.settingsDao().upsert(SettingsEntity(SettingsKeys.TRASH_RETENTION_DAYS, "3"))
        val root = service.ensureRootFolder()
        seedCompletedFile(folderId = root.id)
        service.trashFile("file-1")
        val file = db.fileDao().getById("file-1")!!
        assertEquals(file.deletedAt!! + 3 * dayMs, file.purgeAt)
    }

    @Test
    fun duplicateNameGuardIgnoresTrashedFiles() = runBlocking {
        val root = service.ensureRootFolder()
        db.fileDao().insert(testFile(id = "file-1", folderId = root.id, name = "a.mp4"))
        service.trashFile("file-1")
        assertEquals(0, db.fileDao().countByNameInFolder(root.id, "a.mp4"))
    }

    @Test
    fun listTrashedReturnsNewestFirst() = runBlocking {
        val root = service.ensureRootFolder()
        db.fileDao().insert(testFile(id = "file-1", folderId = root.id))
        advance(1_000)
        db.fileDao().insert(testFile(id = "file-2", folderId = root.id))
        service.trashFile("file-1")
        advance(1_000)
        service.trashFile("file-2")
        assertEquals(listOf("file-2", "file-1"), db.fileDao().listTrashed().map { it.id })
    }
}
