package com.qiradev.cloudnest.engine.upload

import com.qiradev.cloudnest.data.buildTestDb
import com.qiradev.cloudnest.data.entity.FileChunkEntity
import com.qiradev.cloudnest.data.entity.JobStatus
import com.qiradev.cloudnest.data.entity.PlacementStatus
import com.qiradev.cloudnest.engine.providers.TokenPair
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * specs/10 IntegrityTest — per-chunk hash match and whole-file hash; a corrupted
 * remote copy is detected on verify, deleted and re-uploaded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IntegrityTest {

    private lateinit var harness: UploadTestHarness
    private val bytes = ByteArray(17 * 1024 * 1024) { (it % 251).toByte() }

    @Before
    fun setUp(): Unit = runBlocking {
        harness = UploadTestHarness(db = buildTestDb(), chunkSizeMib = 8)
        harness.addAccount("acc-a", freeBytes = 100 * ChunkMath.MIB)
        harness.addAccount("acc-b", freeBytes = 100 * ChunkMath.MIB)
    }

    @Test
    fun wholeFileAndChunkHashesMatchTheSource(): Unit = runBlocking {
        val jobId = harness.enqueueBlocking(InMemoryFileSource(bytes), "video.mp4")
        harness.service.runJob(jobId)

        assertEquals(JobStatus.DONE, harness.db.transferJobDao().getById(jobId)!!.status)
        val file = harness.db.fileDao().getById(harness.db.transferJobDao().getById(jobId)!!.fileId!!)!!
        assertEquals(Hashing.hex(Hashing.sha256(bytes)), file.sha256)

        val chunks = harness.db.fileChunkDao().listByFile(file.id)
        assertEquals(3, chunks.size)
        chunks.forEach { chunk ->
            assertEquals(Hashing.hex(Hashing.sha256(sourceSlice(chunk))), chunk.sha256)
        }
    }

    @Test
    fun corruptedRemoteCopyIsDeletedAndReuploaded(): Unit = runBlocking {
        harness.connector.corruptDownloadsRemaining = 1
        val jobId = harness.enqueueBlocking(InMemoryFileSource(bytes), "video.mp4")

        harness.service.runJob(jobId)

        assertEquals(JobStatus.DONE, harness.db.transferJobDao().getById(jobId)!!.status)
        // The corrupt remote object was deleted before the re-upload.
        assertEquals(1, harness.connector.deletedRemoteIds.size)

        // Every stored chunk now matches the source bytes byte-for-byte.
        val file = harness.db.fileDao().getById(harness.db.transferJobDao().getById(jobId)!!.fileId!!)!!
        harness.db.chunkPlacementDao().listByFile(file.id).forEach { placement ->
            assertEquals(PlacementStatus.VERIFIED, placement.status)
            val stored = harness.connector.downloadChunk(TokenPair("t", null, Long.MAX_VALUE), placement.remoteId!!)
            val chunk = harness.db.fileChunkDao().getById(placement.chunkId)!!
            assertEquals(chunk.sizeBytes, stored.size.toLong())
            assertTrue(stored.contentEquals(sourceSlice(chunk)))
        }
    }

    @Test
    fun fileChangedDuringUploadIsDetected(): Unit = runBlocking {
        // The source grows between planning and uploading.
        val growing = object : UploadFileSource {
            var grown = false
            override fun sizeBytes(): Long = bytes.size + if (grown) 1L else 0L
            override fun openStream(): InputStream {
                grown = true
                return ByteArrayInputStream(bytes)
            }
        }
        val jobId = harness.enqueueBlocking(growing, "mutating.bin")

        harness.service.runJob(jobId)

        val job = harness.db.transferJobDao().getById(jobId)!!
        assertEquals(JobStatus.FAILED, job.status)
        assertEquals(UploadErrorCode.FILE_CHANGED_DURING_UPLOAD, job.errorCode)
    }

    private fun sourceSlice(chunk: FileChunkEntity): ByteArray =
        bytes.copyOfRange(
            (chunk.chunkIndex * 8 * ChunkMath.MIB).toInt(),
            (chunk.chunkIndex * 8 * ChunkMath.MIB + chunk.sizeBytes).toInt(),
        )
}
