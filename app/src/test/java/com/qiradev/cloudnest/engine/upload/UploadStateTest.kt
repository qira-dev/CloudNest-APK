package com.qiradev.cloudnest.engine.upload

import com.qiradev.cloudnest.data.buildTestDb
import com.qiradev.cloudnest.data.entity.FileStatus
import com.qiradev.cloudnest.data.entity.JobKind
import com.qiradev.cloudnest.data.entity.JobStatus
import com.qiradev.cloudnest.data.entity.NotificationType
import com.qiradev.cloudnest.data.entity.PlacementStatus
import com.qiradev.cloudnest.engine.providers.ProviderErrorCode
import com.qiradev.cloudnest.engine.providers.ProviderException
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * specs/10 UploadStateTest — pause/resume/cancel, retry/backoff, Wi-Fi pause,
 * the chunk-size ladder, startup recovery and duplicate naming.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UploadStateTest {

    private lateinit var harness: UploadTestHarness

    @Before
    fun setUp(): Unit = runBlocking {
        harness = UploadTestHarness(db = buildTestDb(), chunkSizeMib = 8)
        harness.addAccount("acc-a", freeBytes = 100 * ChunkMath.MIB)
        harness.addAccount("acc-b", freeBytes = 100 * ChunkMath.MIB)
    }

    @Test
    fun happyPathCompletesWithVerifiedPlacementsAndNotification(): Unit = runBlocking {
        val bytes = ByteArray(17 * 1024 * 1024) { (it % 251).toByte() } // 3 chunks @ 8 MiB
        val jobId = harness.enqueueBlocking(InMemoryFileSource(bytes), "video.mp4")

        harness.service.runJob(jobId)

        val job = harness.db.transferJobDao().getById(jobId)!!
        assertEquals(JobStatus.DONE, job.status)
        assertNull(job.errorCode)

        val file = harness.db.fileDao().getById(job.fileId!!)!!
        assertEquals(FileStatus.COMPLETE, file.status)
        assertEquals(3, file.chunkCount)
        assertEquals(Hashing.hex(Hashing.sha256(bytes)), file.sha256)

        val placements = harness.db.chunkPlacementDao().listByFile(file.id)
        assertEquals(3, placements.size)
        assertTrue(placements.all { it.status == PlacementStatus.VERIFIED })
        assertTrue(placements.all { it.remoteId != null })

        // Reservations are released once the job is done (specs/01 §1.8).
        assertTrue(harness.db.reservationDao().getAll().isEmpty())

        val notifications = harness.db.notificationDao().latest(10)
        assertEquals(listOf(NotificationType.UPLOAD_DONE), notifications.map { it.type })
    }

    @Test
    fun duplicateNamesGetTheParenthesisSuffix(): Unit = runBlocking {
        val source = InMemoryFileSource(ByteArray(10))
        val first = harness.enqueueBlocking(source, "a.mp4")
        val second = harness.enqueueBlocking(source, "a.mp4")
        val firstFileId = harness.db.transferJobDao().getById(first)!!.fileId!!
        val secondFileId = harness.db.transferJobDao().getById(second)!!.fileId!!
        assertEquals("a.mp4", harness.db.fileDao().getById(firstFileId)!!.name)
        assertEquals("a (2).mp4", harness.db.fileDao().getById(secondFileId)!!.name)
    }

    @Test
    fun wifiLossMidUploadPausesAndResumeSkipsVerifiedChunks(): Unit = runBlocking {
        val bytes = ByteArray(17 * 1024 * 1024) { (it % 251).toByte() }
        harness.flipAfterUploads = 1 // gate closes after the first chunk upload
        val jobId = harness.enqueueBlocking(InMemoryFileSource(bytes), "video.mp4")

        harness.service.runJob(jobId)

        var job = harness.db.transferJobDao().getById(jobId)!!
        assertEquals(JobStatus.PAUSED, job.status)
        assertEquals(UploadErrorCode.WIFI_REQUIRED, job.errorCode)
        assertEquals(1, harness.connector.uploadCount)
        // Reservations survive the pause (specs/02 §5).
        assertEquals(17 * ChunkMath.MIB, harness.db.reservationDao().getAll().sumOf { it.bytes })

        // Gate reopens; resume re-runs the job from where it stood.
        harness.flipAfterUploads = -1
        harness.service.resume(jobId)
        harness.service.runJob(jobId)

        job = harness.db.transferJobDao().getById(jobId)!!
        assertEquals(JobStatus.DONE, job.status)
        assertEquals(3, harness.connector.uploadCount) // verified chunk never re-sent
    }

    @Test
    fun cancelReleasesReservationsAndFailsTheFile(): Unit = runBlocking {
        val bytes = ByteArray(17 * 1024 * 1024) { (it % 251).toByte() }
        harness.flipAfterUploads = 1
        val jobId = harness.enqueueBlocking(InMemoryFileSource(bytes), "video.mp4")
        harness.service.runJob(jobId)
        assertEquals(JobStatus.PAUSED, harness.db.transferJobDao().getById(jobId)!!.status)

        harness.service.cancel(jobId)

        val job = harness.db.transferJobDao().getById(jobId)!!
        assertEquals(JobStatus.CANCELLED, job.status)
        assertTrue(harness.db.reservationDao().getAll().isEmpty())
        assertEquals(FileStatus.FAILED, harness.db.fileDao().getById(job.fileId!!)!!.status)
    }

    @Test
    fun networkFailuresRetryWithTheSpeccedBackoff(): Unit = runBlocking {
        harness.connector.uploadFailuresRemaining = 2
        harness.connector.uploadFailure = IOException("fake network drop")
        val jobId = harness.enqueueBlocking(InMemoryFileSource(ByteArray(1024)), "small.bin")

        harness.service.runJob(jobId)

        assertEquals(JobStatus.DONE, harness.db.transferJobDao().getById(jobId)!!.status)
        assertEquals(listOf(5_000L, 15_000L), harness.delays)
    }

    @Test
    fun authExpiredIsTerminalWithNoRetry(): Unit = runBlocking {
        harness.connector.uploadFailuresRemaining = 10
        harness.connector.uploadFailure = ProviderException(ProviderErrorCode.AUTH_EXPIRED)
        val jobId = harness.enqueueBlocking(InMemoryFileSource(ByteArray(1024)), "small.bin")

        harness.service.runJob(jobId)

        val job = harness.db.transferJobDao().getById(jobId)!!
        assertEquals(JobStatus.FAILED, job.status)
        assertEquals(UploadErrorCode.AUTH_EXPIRED, job.errorCode)
        assertTrue(harness.delays.isEmpty())
        assertTrue(harness.db.reservationDao().getAll().isEmpty())
        assertEquals(FileStatus.FAILED, harness.db.fileDao().getById(job.fileId!!)!!.status)
        assertEquals(
            listOf(NotificationType.UPLOAD_FAILED),
            harness.db.notificationDao().latest(1).map { it.type },
        )
    }

    @Test
    fun tooMuchForOneAccountFailsWithNoFreeSpace(): Unit = runBlocking {
        // 17 MiB file against a 10 MiB account: the whole ladder 64→32→16→8 fails.
        val tight = UploadTestHarness(db = buildTestDb(), chunkSizeMib = 64)
        tight.addAccount("tiny", freeBytes = 10 * ChunkMath.MIB)
        val jobId = tight.enqueueBlocking(InMemoryFileSource(ByteArray(17 * 1024 * 1024)), "big.mp4")

        tight.service.runJob(jobId)

        val job = tight.db.transferJobDao().getById(jobId)!!
        assertEquals(JobStatus.FAILED, job.status)
        assertEquals(UploadErrorCode.NO_FREE_SPACE, job.errorCode)
        assertTrue(tight.db.reservationDao().getAll().isEmpty())
    }

    @Test
    fun smallerChunkLadderReplansHashesWhenItSavesThePlan(): Unit = runBlocking {
        // 17 MiB file, two 10 MiB accounts: 64/32/16 MiB chunks do not fit; 8 MiB does.
        val bytes = ByteArray(17 * 1024 * 1024) { (it % 251).toByte() }
        val tight = UploadTestHarness(db = buildTestDb(), chunkSizeMib = 64)
        tight.addAccount("acc-a", freeBytes = 10 * ChunkMath.MIB)
        tight.addAccount("acc-b", freeBytes = 10 * ChunkMath.MIB)

        val jobId = tight.enqueueBlocking(InMemoryFileSource(bytes), "spill.mp4")
        tight.service.runJob(jobId)

        val job = tight.db.transferJobDao().getById(jobId)!!
        assertEquals(JobStatus.DONE, job.status)
        val file = tight.db.fileDao().getById(job.fileId!!)!!
        assertEquals(8, file.chunkSizeMib)
        assertEquals(3, file.chunkCount)
        assertEquals(Hashing.hex(Hashing.sha256(bytes)), file.sha256)
    }

    @Test
    fun startupRecoveryRequeuesRunningJobsAndDropsDeadReservations(): Unit = runBlocking {
        val aliveJob = harness.enqueueBlocking(InMemoryFileSource(ByteArray(1024)), "alive.bin")
        // Simulate a crash mid-run: the row stayed `running` with its reservation.
        val job = harness.db.transferJobDao().getById(aliveJob)!!
        harness.db.transferJobDao().update(job.copy(status = JobStatus.RUNNING))
        val accountId = harness.db.providerAccountDao().getAll().first().id
        harness.db.reservationDao().insert(
            com.qiradev.cloudnest.data.entity.ReservationEntity(
                id = "alive-res",
                accountId = accountId,
                bytes = 123,
                jobId = aliveJob,
                createdAt = 1,
            ),
        )
        // A reservation of a dead (done) job must be swept.
        harness.db.transferJobDao().insert(
            com.qiradev.cloudnest.data.entity.TransferJobEntity(
                id = "dead-job",
                kind = JobKind.UPLOAD,
                fileId = null,
                status = JobStatus.DONE,
                errorCode = null,
                attempts = 0,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        harness.db.reservationDao().insert(
            com.qiradev.cloudnest.data.entity.ReservationEntity(
                id = "dead-res",
                accountId = accountId,
                bytes = 7,
                jobId = "dead-job",
                createdAt = 1,
            ),
        )

        harness.service.recoverAtStartup()

        // The crashed job returns to the queue and KEEPS its reservation (it will resume).
        assertEquals(JobStatus.QUEUED, harness.db.transferJobDao().getById(aliveJob)!!.status)
        assertEquals(
            setOf("alive-res"),
            harness.db.reservationDao().getAll().map { it.id }.toSet(),
        )
    }

    @Test
    fun emptyFileCompletesWithoutChunks(): Unit = runBlocking {
        val jobId = harness.enqueueBlocking(InMemoryFileSource(ByteArray(0)), "empty.txt")
        harness.service.runJob(jobId)

        val job = harness.db.transferJobDao().getById(jobId)!!
        assertEquals(JobStatus.DONE, job.status)
        val file = harness.db.fileDao().getById(job.fileId!!)!!
        assertEquals(FileStatus.COMPLETE, file.status)
        assertEquals(0, file.chunkCount)
        assertTrue(harness.db.fileChunkDao().listByFile(file.id).isEmpty())
    }
}
