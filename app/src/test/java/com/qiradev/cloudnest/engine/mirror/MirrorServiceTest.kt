package com.qiradev.cloudnest.engine.mirror

import com.qiradev.cloudnest.data.buildTestDb
import com.qiradev.cloudnest.data.entity.FileStatus
import com.qiradev.cloudnest.data.entity.PlacementRole
import com.qiradev.cloudnest.data.entity.PlacementStatus
import com.qiradev.cloudnest.data.entity.ProviderAccountEntity
import com.qiradev.cloudnest.data.entity.ProviderAccountStatus
import com.qiradev.cloudnest.data.entity.QuotaStatus
import com.qiradev.cloudnest.engine.providers.FakeConnector
import com.qiradev.cloudnest.engine.providers.TokenPair
import com.qiradev.cloudnest.engine.upload.ChunkMath
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
 * specs/06 §1 + specs/10 — mirror job behavior on the FakeConnector: selection
 * (same provider, never the primary's account), verified mirror placements,
 * partial failure leaving the file usable, notifications.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MirrorServiceTest {

    private lateinit var db: com.qiradev.cloudnest.data.CloudNestDatabase
    private lateinit var connector: FakeConnector
    private lateinit var service: MirrorService
    private var now = 1_000_000_000_000L

    @Before
    fun setUp() = runBlocking {
        db = buildTestDb()
        connector = FakeConnector()
        service = MirrorService(
            db = db,
            connectors = mapOf("google_drive" to connector),
            tokens = { TokenPair("test-token", null, now + 3_600_000) },
            clock = { ++now },
        )
    }

    private suspend fun addAccount(id: String, freeBytes: Long = 100 * ChunkMath.MIB) {
        db.providerAccountDao().insert(
            ProviderAccountEntity(
                id = id,
                provider = "google_drive",
                label = id,
                status = ProviderAccountStatus.CONNECTED,
                encryptedTokens = null,
                tokenExpiresAt = 0,
                quotaTotalBytes = freeBytes,
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

    /** A complete one-chunk file with a verified primary placement on acc-1. */
    private suspend fun seedCompletedFile(
        fileId: String = "file-1",
        chunkCount: Int = 1,
    ) {
        if (db.folderDao().getById("folder-1") == null) {
            com.qiradev.cloudnest.data.testFolder(id = "folder-1", parent = null, name = "").let {
                db.folderDao().insert(it)
            }
        }
        db.fileDao().insert(
            com.qiradev.cloudnest.data.testFile(id = fileId, folderId = "folder-1")
                .copy(status = FileStatus.COMPLETE, chunkCount = chunkCount),
        )
        for (index in 0 until chunkCount) {
            val bytes = ByteArray(64) { (it + index).toByte() }
            val chunkId = "chunk-$fileId-$index"
            db.fileChunkDao().insert(
                com.qiradev.cloudnest.data.testChunk(id = chunkId, fileId = fileId, index = index)
                    .copy(sizeBytes = bytes.size.toLong(), sha256 = sha256Hex(bytes)),
            )
            val remoteId = "remote-primary-$fileId-$index"
            connector.chunks[remoteId.removePrefix("remote-")] = bytes
            db.chunkPlacementDao().insert(
                com.qiradev.cloudnest.data.testPlacement(
                    id = "pl-primary-$fileId-$index",
                    chunkId = chunkId,
                    accountId = "acc-1",
                ).copy(
                    role = PlacementRole.PRIMARY,
                    remoteId = remoteId,
                    status = PlacementStatus.VERIFIED,
                    uploadedAt = now,
                ),
            )
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        com.qiradev.cloudnest.engine.upload.Hashing.hex(
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes),
        )

    private suspend fun runMirrorFor(fileId: String = "file-1"): String {
        val jobId = service.enqueueJob(fileId)!!
        service.runJob(jobId)
        return jobId
    }

    // ---- enqueue gating ----

    @Test
    fun enqueueRequiresTwoSameProviderAccounts() = runBlocking {
        addAccount("acc-1")
        seedCompletedFile()
        assertNull(service.enqueueJob("file-1"))

        addAccount("acc-2")
        assertNotNull(service.enqueueJob("file-1"))
    }

    @Test
    fun enqueueOnlyForCompleteFilesAndNoDuplicates() = runBlocking {
        addAccount("acc-1")
        addAccount("acc-2")
        seedCompletedFile()
        val first = service.enqueueJob("file-1")!!
        assertNull(service.enqueueJob("file-1")) // already queued
        assertTrue(first.isNotBlank())
    }

    // ---- mirror run ----

    @Test
    fun runCreatesVerifiedMirrorPlacementsOnAnotherAccount() = runBlocking {
        addAccount("acc-1")
        addAccount("acc-2")
        seedCompletedFile(chunkCount = 2)

        runMirrorFor()

        val placements = db.chunkPlacementDao().listByFile("file-1")
        val mirrors = placements.filter { it.role == PlacementRole.MIRROR }
        assertEquals(2, mirrors.size)
        mirrors.forEach { mirror ->
            assertEquals(PlacementStatus.VERIFIED, mirror.status)
            assertEquals("acc-2", mirror.accountId)
            assertNotNull(mirror.remoteId)
            // The mirror copy holds exactly the chunk bytes.
            val chunk = db.fileChunkDao().listByFile("file-1").first { it.id == mirror.chunkId }
            val stored = connector.chunks.getValue(mirror.remoteId!!.removePrefix("remote-"))
            assertEquals(sha256Hex(stored), chunk.sha256)
        }
        assertEquals("mirror_done", db.notificationDao().latest(5).first().type)
        assertEquals("done", db.transferJobDao().listByKindAndStatus("mirror", "done").single().status)
    }

    @Test
    fun mirrorNeverLandsOnThePrimaryAccount() = runBlocking {
        addAccount("acc-1")
        addAccount("acc-2")
        addAccount("acc-3")
        seedCompletedFile(chunkCount = 3)

        runMirrorFor()

        val mirrors = db.chunkPlacementDao().listByFile("file-1").filter { it.role == PlacementRole.MIRROR }
        assertEquals(3, mirrors.size)
        assertTrue(mirrors.all { it.accountId != "acc-1" })
    }

    @Test
    fun alreadyMirroredChunksAreSkippedOnRerun() = runBlocking {
        addAccount("acc-1")
        addAccount("acc-2")
        seedCompletedFile()
        runMirrorFor()
        val uploadsAfterFirst = connector.uploadCount

        // Fresh job over a fully mirrored file: verified mirrors are skipped.
        val jobId = service.enqueueJob("file-1")!!
        service.runJob(jobId)
        assertEquals(uploadsAfterFirst, connector.uploadCount)

        // One mirror copy lost → re-run uploads exactly one replacement.
        val mirror = db.chunkPlacementDao().listByFile("file-1").first { it.role == PlacementRole.MIRROR }
        db.chunkPlacementDao().update(mirror.copy(status = PlacementStatus.FAILED))
        val retryJob = service.enqueueJob("file-1")!!
        service.runJob(retryJob)
        assertEquals(uploadsAfterFirst + 1, connector.uploadCount)
    }

    @Test
    fun insufficientSpaceMeansNoMirrorAccount() = runBlocking {
        addAccount("acc-1")
        addAccount("acc-2", freeBytes = 10) // too small for the chunk
        seedCompletedFile()

        val jobId = service.enqueueJob("file-1")!!
        service.runJob(jobId)

        val job = db.transferJobDao().getById(jobId)!!
        assertEquals("failed", job.status)
        assertEquals(MirrorErrorCode.NO_MIRROR_ACCOUNT, job.errorCode)
        assertEquals("mirror_failed", db.notificationDao().latest(5).first().type)
        // The file keeps its primary copy — still complete and usable.
        assertEquals(FileStatus.COMPLETE, db.fileDao().getById("file-1")!!.status)
        val primaries = db.chunkPlacementDao().listByFile("file-1")
            .filter { it.role == PlacementRole.PRIMARY }
        assertTrue(primaries.all { it.status == PlacementStatus.VERIFIED })
    }

    @Test
    fun downloadStillWorksWhenMirrorFailed() = runBlocking {
        addAccount("acc-1")
        addAccount("acc-2", freeBytes = 10)
        seedCompletedFile()
        runMirrorFor()

        // Chunk fetcher (specs/03) serves from the primary placement.
        val chunk = db.fileChunkDao().listByFile("file-1").first()
        val fetcher = com.qiradev.cloudnest.engine.download.ChunkFetcher(
            db = db,
            connectors = mapOf("google_drive" to connector),
            tokens = { TokenPair("t", null, now + 3_600_000) },
            delayFn = { },
        )
        val bytes = fetcher.fetch(chunk)
        assertEquals(sha256Hex(bytes), chunk.sha256)
    }

    @Test
    fun corruptSourceIsRejectedBeforeMirroring() = runBlocking {
        addAccount("acc-1")
        addAccount("acc-2")
        seedCompletedFile()
        // Poison the primary remote copy in the fake store.
        val primary = db.chunkPlacementDao().listByFile("file-1").first { it.role == PlacementRole.PRIMARY }
        connector.chunks[primary.remoteId!!.removePrefix("remote-")] =
            ByteArray(64) { (it + 99).toByte() }

        val jobId = service.enqueueJob("file-1")!!
        service.runJob(jobId)

        assertEquals("failed", db.transferJobDao().getById(jobId)!!.status)
        assertEquals(MirrorErrorCode.CHUNK_HASH_MISMATCH, db.transferJobDao().getById(jobId)!!.errorCode)
        assertTrue(
            db.chunkPlacementDao().listByFile("file-1").none { it.role == PlacementRole.MIRROR },
        )
    }

    @Test
    fun failedMirrorJobCanBeRetried() = runBlocking {
        addAccount("acc-1")
        addAccount("acc-2", freeBytes = 10)
        seedCompletedFile()
        val jobId = service.enqueueJob("file-1")!!
        service.runJob(jobId)
        assertEquals("failed", db.transferJobDao().getById(jobId)!!.status)

        // Widen the mirror account, then retry the same job.
        val account = db.providerAccountDao().getById("acc-2")!!
        db.providerAccountDao().update(account.copy(quotaTotalBytes = 100 * ChunkMath.MIB))
        service.retry(jobId)
        service.runJob(jobId)

        assertEquals("done", db.transferJobDao().getById(jobId)!!.status)
        assertEquals(
            1,
            db.chunkPlacementDao().listByFile("file-1").count { it.role == PlacementRole.MIRROR },
        )
    }

    @Test
    fun startupRecoveryRequeuesRunningMirrorJobs() = runBlocking {
        addAccount("acc-1")
        addAccount("acc-2")
        seedCompletedFile()
        val jobId = service.enqueueJob("file-1")!!
        db.transferJobDao().update(db.transferJobDao().getById(jobId)!!.copy(status = "running"))

        service.recoverAtStartup()

        assertEquals("queued", db.transferJobDao().getById(jobId)!!.status)
    }
}
