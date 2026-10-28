package com.qiradev.cloudnest.engine.download

import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.entity.ChunkPlacementEntity
import com.qiradev.cloudnest.data.entity.FileChunkEntity
import com.qiradev.cloudnest.data.entity.PlacementRole
import com.qiradev.cloudnest.data.entity.PlacementStatus
import com.qiradev.cloudnest.engine.providers.ProviderConnector
import com.qiradev.cloudnest.engine.providers.ProviderErrorCode
import com.qiradev.cloudnest.engine.providers.ProviderException
import com.qiradev.cloudnest.engine.upload.TokenProvider
import java.io.IOException

/**
 * Chunk fetching (specs/03 §2): primary copy first, mirror on failure;
 * 3 attempts with 5s/15s/60s backoff per copy; every copy is hash-verified —
 * a copy delivering wrong bytes is marked suspicious (FAILED) for diagnostics.
 */
class ChunkFetcher(
    private val db: CloudNestDatabase,
    private val connectors: Map<String, ProviderConnector>,
    private val tokens: TokenProvider,
    private val delayFn: suspend (Long) -> Unit,
) {
    suspend fun fetch(chunk: FileChunkEntity): ByteArray {
        val placements = db.chunkPlacementDao().listByChunk(chunk.id)
            .sortedBy { if (it.role == PlacementRole.PRIMARY) 0 else 1 }
        if (placements.isEmpty()) throw DownloadJobException(DownloadErrorCode.CHUNK_UNAVAILABLE)

        var lastError: DownloadJobException = DownloadJobException(DownloadErrorCode.UNKNOWN)
        for (placement in placements) {
            try {
                val bytes = downloadWithRetries(placement)
                if (Verifier.matches(bytes, chunk)) return bytes
                markSuspicious(placement)
                lastError = DownloadJobException(DownloadErrorCode.CHUNK_HASH_MISMATCH)
            } catch (e: DownloadJobException) {
                if (e.errorCode == DownloadErrorCode.CHUNK_HASH_MISMATCH) markSuspicious(placement)
                lastError = e
            }
        }
        throw lastError
    }

    private suspend fun downloadWithRetries(placement: ChunkPlacementEntity): ByteArray {
        val account = db.providerAccountDao().getById(placement.accountId)
            ?: throw DownloadJobException(DownloadErrorCode.UNKNOWN)
        val connector = connectors[account.provider]
            ?: throw DownloadJobException(DownloadErrorCode.UNKNOWN)
        val remoteId = requireNotNull(placement.remoteId) { "placement without remote_id" }

        var attempt = 1
        while (true) {
            try {
                return connector.downloadChunk(tokens.validTokens(account.id), remoteId)
            } catch (e: Exception) {
                val code = errorCodeOf(e)
                if (!isRetryable(code) || attempt >= MAX_ATTEMPTS) {
                    throw DownloadJobException(code, e)
                }
                delayFn(BACKOFF_MS[attempt - 1])
                attempt++
            }
        }
    }

    private suspend fun markSuspicious(placement: ChunkPlacementEntity) {
        db.chunkPlacementDao().update(placement.copy(status = PlacementStatus.FAILED))
    }

    private fun errorCodeOf(e: Throwable): String = when (e) {
        is DownloadJobException -> e.errorCode
        is ProviderException -> when (e.code) {
            ProviderErrorCode.PROVIDER_DOWN -> DownloadErrorCode.PROVIDER_DOWN
            ProviderErrorCode.PROVIDER_RATE_LIMITED -> DownloadErrorCode.PROVIDER_DOWN
            ProviderErrorCode.AUTH_EXPIRED -> DownloadErrorCode.AUTH_EXPIRED
            else -> DownloadErrorCode.UNKNOWN
        }
        is IOException -> DownloadErrorCode.NETWORK_UNREACHABLE
        else -> DownloadErrorCode.UNKNOWN
    }

    private fun isRetryable(code: String): Boolean = code in setOf(
        DownloadErrorCode.NETWORK_UNREACHABLE,
        DownloadErrorCode.PROVIDER_DOWN,
    )

    private companion object {
        const val MAX_ATTEMPTS = 3
        val BACKOFF_MS = longArrayOf(5_000L, 15_000L, 60_000L)
    }
}
