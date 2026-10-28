package com.qiradev.cloudnest.engine.providers

import android.net.Uri

/**
 * specs/10 §3 — FakeConnector: in-memory implementation of the provider contract
 * for engine tests; quota is configurable and failures are injectable.
 */
class FakeConnector(override val id: String = "google_drive") : ProviderConnector {

    var quota: Quota? = Quota(totalBytes = 1_000L, usedBytes = 100L, unlimited = false)

    var exchangeException: ProviderException? = null
    var refreshException: ProviderException? = null
    var readQuotaException: ProviderException? = null

    var exchangeCount = 0
        private set
    var refreshCount = 0
        private set
    var ensureRootCount = 0
        private set

    val chunks = mutableMapOf<String, ByteArray>()
    val deletedRemoteIds = mutableListOf<String>()

    override suspend fun buildAuthUrl(state: String): Uri =
        Uri.parse("https://fake.example/oauth/authorize?state=$state")

    override suspend fun exchangeCode(code: String): TokenPair {
        exchangeCount++
        exchangeException?.let { throw it }
        return TokenPair("access-for-$code", "refresh-1", expiresAtMs = nowMs() + 60 * 60 * 1000)
    }

    override suspend fun refreshToken(refreshToken: String): TokenPair {
        refreshCount++
        refreshException?.let { throw it }
        return TokenPair("access-refreshed-$refreshCount", refreshToken, expiresAtMs = nowMs() + 60 * 60 * 1000)
    }

    override suspend fun readQuota(tokens: TokenPair): Quota? {
        readQuotaException?.let { throw it }
        return quota
    }

    override suspend fun ensureRoot(tokens: TokenPair): String {
        ensureRootCount++
        return "fake-root"
    }

    override suspend fun uploadChunk(tokens: TokenPair, root: String, name: String, bytes: ByteArray): String {
        require(root == "fake-root")
        chunks[name] = bytes
        return "remote-$name"
    }

    override suspend fun downloadChunk(tokens: TokenPair, remoteId: String): ByteArray {
        require(remoteId.startsWith("remote-"))
        return chunks.getValue(remoteId.removePrefix("remote-"))
    }

    override suspend fun deleteObject(tokens: TokenPair, remoteId: String) {
        deletedRemoteIds.add(remoteId)
        chunks.remove(remoteId.removePrefix("remote-"))
    }

    private fun nowMs(): Long = System.currentTimeMillis()
}
