package com.qiradev.cloudnest.engine.upload

import java.io.InputStream
import java.security.MessageDigest

/** Chunk math (specs/02 §1) — ported 1:1 from the old chunk-math logic. */
object ChunkMath {
    const val MIB = 1024L * 1024L
    val ALLOWED_CHUNK_SIZES_MIB = listOf(8, 16, 32, 64)
    const val DEFAULT_CHUNK_SIZE_MIB = 64

    fun isValidChunkSizeMib(sizeMib: Int): Boolean = sizeMib in ALLOWED_CHUNK_SIZES_MIB

    /** Next step down the 64→32→16→8 ladder, or NULL at the bottom (specs/02 §3.3). */
    fun suggestSmallerChunkSizeMib(sizeMib: Int): Int? {
        val index = ALLOWED_CHUNK_SIZES_MIB.indexOf(sizeMib)
        require(index >= 0) { "invalid chunk size: $sizeMib" }
        return if (index == 0) null else ALLOWED_CHUNK_SIZES_MIB[index - 1]
    }

    /** Empty files have 0 chunks (specs/02 §1). */
    fun chunkCount(sizeBytes: Long, chunkSizeMib: Int): Int {
        require(isValidChunkSizeMib(chunkSizeMib)) { "invalid chunk size: $chunkSizeMib" }
        require(sizeBytes >= 0)
        if (sizeBytes == 0L) return 0
        val chunkBytes = chunkSizeMib * MIB
        return ((sizeBytes + chunkBytes - 1) / chunkBytes).toInt()
    }

    /** Last chunk is smaller when the size does not divide evenly. */
    fun chunkSizeAt(sizeBytes: Long, chunkSizeMib: Int, index: Int): Long {
        val count = chunkCount(sizeBytes, chunkSizeMib)
        require(index in 0 until count) { "chunk index $index out of range (count=$count)" }
        val chunkBytes = chunkSizeMib * MIB
        val fullBefore = index.toLong() * chunkBytes
        return minOf(chunkBytes, sizeBytes - fullBefore)
    }

    fun chunkSizeBytes(chunkSizeMib: Int): Long = chunkSizeMib * MIB
}

/** Streaming SHA-256 helpers — the file never sits in memory whole (specs/02 §9). */
object Hashing {
    private const val BUFFER = 64 * 1024

    fun sha256(stream: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest()
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Source of the file being uploaded. Production backs this with SAF's
 * ContentResolver stream; tests use in-memory bytes. One [openStream] per read.
 */
interface UploadFileSource {
    fun sizeBytes(): Long
    fun openStream(): InputStream
}

class InMemoryFileSource(private val bytes: ByteArray) : UploadFileSource {
    override fun sizeBytes(): Long = bytes.size.toLong()
    override fun openStream(): InputStream = bytes.inputStream()
}
