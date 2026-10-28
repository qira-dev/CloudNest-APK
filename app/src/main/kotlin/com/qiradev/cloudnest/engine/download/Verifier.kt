package com.qiradev.cloudnest.engine.download

import com.qiradev.cloudnest.data.entity.FileChunkEntity

/** Fixed safe error codes (specs/03 §6). */
object DownloadErrorCode {
    const val CHUNK_UNAVAILABLE = "chunk_unavailable"
    const val CHUNK_HASH_MISMATCH = "chunk_hash_mismatch"
    const val NETWORK_UNREACHABLE = "network_unreachable"
    const val PROVIDER_DOWN = "provider_down"
    const val AUTH_EXPIRED = "auth_expired"
    const val STORAGE_FULL = "storage_full"
    const val FILE_CHANGED = "file_changed"
    const val CANCELLED = "cancelled"
    const val UNKNOWN = "unknown"
}

/** Terminal download failure carrying the safe error code. */
class DownloadJobException(val errorCode: String, cause: Throwable? = null) : Exception(errorCode, cause)

/** Size + SHA-256 verification of fetched chunk bytes (specs/03 §2). */
object Verifier {
    fun matches(bytes: ByteArray, chunk: FileChunkEntity): Boolean =
        bytes.size.toLong() == chunk.sizeBytes &&
            com.qiradev.cloudnest.engine.upload.Hashing.sha256(bytes).contentEquals(chunk.sha256.hexToBytes())

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
