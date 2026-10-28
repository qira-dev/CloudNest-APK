package com.qiradev.cloudnest.data

import android.database.sqlite.SQLiteConstraintException
import com.qiradev.cloudnest.data.entity.JobStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * specs/10 SchemaTest — every foreign key of specs/01 gets exercised, plus the
 * unique (file_id, chunk_index) index and the startup-recovery query.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ForeignKeyTest {

    private lateinit var db: CloudNestDatabase

    @Before
    fun setUp() {
        db = buildTestDb()
    }

    @Test
    fun fileRequiresExistingFolder(): Unit = runBlocking {
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.fileDao().insert(testFile(folderId = "missing-folder")) }
        }
    }

    @Test
    fun childFolderRequiresExistingParent(): Unit = runBlocking {
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.folderDao().insert(testFolder(id = "child", parent = "missing-parent")) }
        }
    }

    @Test
    fun placementRequiresExistingAccount(): Unit = runBlocking {
        db.folderDao().insert(testFolder())
        db.fileDao().insert(testFile())
        db.fileChunkDao().insert(testChunk())
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.chunkPlacementDao().insert(testPlacement(accountId = "missing-acc")) }
        }
    }

    @Test
    fun deletingFolderWithFilesIsRestricted(): Unit = runBlocking {
        db.folderDao().insert(testFolder())
        db.fileDao().insert(testFile())
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.folderDao().delete("folder-1") }
        }
        assertEquals(1, db.fileDao().listByFolder("folder-1").size)
    }

    @Test
    fun deletingParentFolderWithChildrenIsRestricted(): Unit = runBlocking {
        db.folderDao().insert(testFolder(id = "parent", parent = null, name = "p"))
        db.folderDao().insert(testFolder(id = "child", parent = "parent", name = "c"))
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.folderDao().delete("parent") }
        }
        assertEquals("parent", db.folderDao().getById("child")?.parentId)
    }

    @Test
    fun deletingAccountWithPlacementsIsRestricted(): Unit = runBlocking {
        seedPlacementChain()
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.providerAccountDao().delete("acc-1") }
        }
        assertEquals("acc-1", db.chunkPlacementDao().getById("pl-1")?.accountId)
    }

    @Test
    fun deletingFileCascadesChunksAndSetsJobFileIdNull(): Unit = runBlocking {
        db.folderDao().insert(testFolder())
        db.fileDao().insert(testFile())
        db.fileChunkDao().insert(testChunk())
        db.transferJobDao().insert(testJob(id = "job-1", fileId = "file-1"))

        db.fileDao().delete("file-1")

        assertEquals(0, db.fileChunkDao().countByFile("file-1"))
        assertNull(db.transferJobDao().getById("job-1")?.fileId)
        assertEquals(JobStatus.QUEUED, db.transferJobDao().getById("job-1")?.status)
    }

    @Test
    fun deletingChunkCascadesPlacements(): Unit = runBlocking {
        seedPlacementChain()
        db.fileChunkDao().delete("chunk-1")
        assertTrue(db.chunkPlacementDao().listByChunk("chunk-1").isEmpty())
    }

    @Test
    fun deletingAccountCascadesReservations(): Unit = runBlocking {
        // No placements on this account — placements RESTRICT the delete (covered above).
        db.providerAccountDao().insert(testAccount(id = "acc-9"))
        db.transferJobDao().insert(testJob(id = "job-9", fileId = null))
        db.reservationDao().insert(testReservation(id = "res-9", accountId = "acc-9", jobId = "job-9"))

        db.providerAccountDao().delete("acc-9")
        assertTrue(db.reservationDao().getAll().isEmpty())
    }

    @Test
    fun deletingJobCascadesReservations(): Unit = runBlocking {
        seedPlacementChain()
        db.transferJobDao().insert(testJob(id = "job-1", fileId = null))
        db.reservationDao().insert(testReservation(id = "res-1", accountId = "acc-1", jobId = "job-1"))

        db.transferJobDao().delete("job-1")
        assertTrue(db.reservationDao().getAll().isEmpty())
    }

    @Test
    fun deletingPlacementCascadesUploadAttempts(): Unit = runBlocking {
        seedPlacementChain()
        db.uploadAttemptDao().insert(testUploadAttempt(id = "att-1", placementId = "pl-1"))

        db.chunkPlacementDao().delete("pl-1")
        assertTrue(db.uploadAttemptDao().listByPlacement("pl-1").isEmpty())
    }

    @Test
    fun deletingFileCascadesDeletionAttempts(): Unit = runBlocking {
        db.folderDao().insert(testFolder())
        db.fileDao().insert(testFile())
        db.deletionAttemptDao().insert(testDeletionAttempt())

        db.fileDao().delete("file-1")
        assertTrue(db.deletionAttemptDao().listByFile("file-1").isEmpty())
    }

    @Test
    fun duplicateChunkIndexInSameFileIsRejected(): Unit = runBlocking {
        db.folderDao().insert(testFolder())
        db.fileDao().insert(testFile())
        db.fileChunkDao().insert(testChunk(id = "c-a", index = 0))
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.fileChunkDao().insert(testChunk(id = "c-b", index = 0)) }
        }
    }

    @Test
    fun startupRecoveryDropsOrphanReservations(): Unit = runBlocking {
        val fkOff = buildTestDbWithoutForeignKeys()
        try {
            // Robolectric enables foreign keys by default; restore the SQLite default
            // to simulate the restored-legacy-database case this query exists for.
            fkOff.openHelper.writableDatabase.query("PRAGMA foreign_keys=OFF").use { it.moveToFirst() }

            fkOff.transferJobDao().insert(testJob(id = "job-1", fileId = null))
            fkOff.reservationDao().insert(testReservation(id = "res-1", accountId = "acc-1", jobId = "job-1"))
            fkOff.transferJobDao().delete("job-1")

            assertEquals(1, fkOff.reservationDao().deleteOrphaned())
            assertTrue(fkOff.reservationDao().getAll().isEmpty())
            assertEquals(0, fkOff.reservationDao().deleteOrphaned())
        } finally {
            fkOff.close()
        }
    }

    private suspend fun seedPlacementChain() {
        db.providerAccountDao().insert(testAccount())
        db.folderDao().insert(testFolder())
        db.fileDao().insert(testFile())
        db.fileChunkDao().insert(testChunk())
        db.chunkPlacementDao().insert(testPlacement())
    }
}
