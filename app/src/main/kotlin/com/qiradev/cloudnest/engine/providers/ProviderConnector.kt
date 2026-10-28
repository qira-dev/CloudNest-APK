package com.qiradev.cloudnest.engine.providers

import android.net.Uri

/**
 * The single provider contract (specs/04 §1). Everything else in the engines
 * (specs/02–03) runs on this interface and stays provider-agnostic.
 * Tokens are never logged and never included in error messages.
 */
interface ProviderConnector {
    /** e.g. "google_drive" — matches provider_accounts.provider. */
    val id: String

    suspend fun buildAuthUrl(state: String): Uri
    suspend fun exchangeCode(code: String): TokenPair
    suspend fun refreshToken(refreshToken: String): TokenPair
    suspend fun readQuota(tokens: TokenPair): Quota?
    suspend fun ensureRoot(tokens: TokenPair): String
    suspend fun uploadChunk(tokens: TokenPair, root: String, name: String, bytes: ByteArray): String
    suspend fun downloadChunk(tokens: TokenPair, remoteId: String): ByteArray
    suspend fun deleteObject(tokens: TokenPair, remoteId: String)
}

/** OAuth token set returned by exchange/refresh. [refreshToken] is absent on some refreshes. */
data class TokenPair(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
)

/** Provider quota observation; NULL-safe fields — NULL means "unknown". */
data class Quota(
    val totalBytes: Long?,
    val usedBytes: Long?,
    val unlimited: Boolean,
)

/** Safe error codes (specs/04 §5) — no provider internals ever leak through these. */
object ProviderErrorCode {
    const val AUTH_EXPIRED = "auth_expired"
    const val PROVIDER_DOWN = "provider_down"
    const val PROVIDER_RATE_LIMITED = "provider_rate_limited"
    const val QUOTA_UNKNOWN = "quota_unknown"
}

class ProviderException(val code: String, cause: Throwable? = null) : Exception(code, cause) {
    /** Account status implied by the code (specs/04 §5): only auth expiry flips to error. */
    val accountStatus: String
        get() = if (code == ProviderErrorCode.AUTH_EXPIRED) "error" else "connected"
}
