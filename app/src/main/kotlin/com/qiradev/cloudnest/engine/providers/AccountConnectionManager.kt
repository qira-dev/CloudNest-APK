package com.qiradev.cloudnest.engine.providers

import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.dao.ProviderAccountDao
import com.qiradev.cloudnest.data.entity.AuditEventEntity
import com.qiradev.cloudnest.data.entity.AuditEventType
import com.qiradev.cloudnest.data.entity.ProviderAccountEntity
import com.qiradev.cloudnest.data.entity.ProviderAccountStatus
import com.qiradev.cloudnest.data.entity.QuotaStatus
import com.qiradev.cloudnest.engine.health.HealthTracker
import java.util.UUID

/**
 * Drives the connect/refresh/disconnect flows of specs/04 §2+§4 on top of the
 * provider contract and the Room data layer. Tokens only exist encrypted.
 * Quota-call outcomes feed the health ledger (specs/06 §2.1).
 */
class AccountConnectionManager(
    private val db: CloudNestDatabase,
    private val tokenStore: TokenStore,
    private val connectors: Map<String, ProviderConnector>,
    private val stateStore: OAuthStateStore,
    private val health: HealthTracker? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val accountDao: ProviderAccountDao get() = db.providerAccountDao()

    fun connectorFor(provider: String): ProviderConnector =
        connectors[provider] ?: throw IllegalArgumentException("unknown provider: $provider")

    /** Step 1 of the flow: mint a one-time state and return the consent URL. */
    suspend fun startConnection(provider: String): String {
        val connector = connectorFor(provider)
        val state = stateStore.newOauthState(provider)
        return connector.buildAuthUrl(state).toString()
    }

    /**
     * Steps 2–5: verify the redirect state, exchange the code, store encrypted
     * tokens, observe the first quota, ensure the chunk root, audit + connect.
     * Returns the new account id. Any state mismatch aborts before any network call.
     */
    suspend fun completeConnection(code: String, state: String): String {
        val provider = stateStore.validateAndConsume(state)
            ?: throw ProviderException("state_mismatch")
        val connector = connectorFor(provider)
        val now = clock()

        val tokens = connector.exchangeCode(code)
        val accountId = UUID.randomUUID().toString()
        val encrypted = tokenStore.serialize(tokens.accessToken, tokens.refreshToken, tokens.expiresAtMs)

        accountDao.insert(
            ProviderAccountEntity(
                id = accountId,
                provider = provider,
                label = "",
                status = ProviderAccountStatus.CONNECTED,
                encryptedTokens = encrypted,
                tokenExpiresAt = tokens.expiresAtMs,
                quotaTotalBytes = null,
                quotaUsedBytes = null,
                quotaUnlimited = 0,
                quotaStatus = QuotaStatus.UNKNOWN,
                quotaObservedAt = 0,
                quotaScopeKey = null,
                rootPath = null,
                createdAt = now,
                lastUsedAt = now,
            ),
        )

        observeQuota(accountId, connector, tokens)
        runCatching { connector.ensureRoot(tokens) }
            .onSuccess { root -> accountDao.update(account(accountId).copy(rootPath = root)) }

        audit(AuditEventType.ACCOUNT_CONNECTED, accountId)
        return accountId
    }

    /**
     * Returns usable tokens, refreshing first when they expire within 5 minutes
     * (specs/04 §4). A failed refresh marks the account error (`auth_expired`).
     */
    suspend fun getValidTokens(accountId: String): TokenPair {
        val account = account(accountId)
        val stored = requireNotNull(account.encryptedTokens?.let { tokenStore.restore(it) }) {
            "account $accountId has no stored tokens"
        }
        if (stored.expiresAtMs - clock() > REFRESH_MARGIN_MS) {
            return TokenPair(stored.accessToken, stored.refreshToken, stored.expiresAtMs)
        }
        val refreshToken = requireNotNull(stored.refreshToken) { "no refresh token for $accountId" }
        return try {
            val fresh = connectorFor(account.provider).refreshToken(refreshToken)
            val merged = TokenPair(
                accessToken = fresh.accessToken,
                refreshToken = fresh.refreshToken ?: stored.refreshToken,
                expiresAtMs = fresh.expiresAtMs,
            )
            accountDao.update(
                account.copy(
                    encryptedTokens = tokenStore.serialize(merged.accessToken, merged.refreshToken, merged.expiresAtMs),
                    tokenExpiresAt = merged.expiresAtMs,
                    status = ProviderAccountStatus.CONNECTED,
                    lastUsedAt = clock(),
                ),
            )
            merged
        } catch (e: ProviderException) {
            // specs/04 §5: account flips to error; the re-login notice goes through the
            // OS notification channel (specs/05, step 7) — the audit type set is fixed.
            accountDao.update(account.copy(status = ProviderAccountStatus.ERROR, lastUsedAt = clock()))
            throw e
        }
    }

    /** Regular quota observation (specs/04 §4): the token-health clock. */
    suspend fun refreshQuota(accountId: String) {
        val account = account(accountId)
        val tokens = try {
            getValidTokens(accountId)
        } catch (e: ProviderException) {
            health?.recordFailure(accountId, e.code, kind = "quota")
            if (e.code == ProviderErrorCode.AUTH_EXPIRED) {
                throw e
            }
            // Down/rate-limited/unreadable keep the account connected; routing skips it.
            accountDao.update(account.copy(quotaStatus = QuotaStatus.UNKNOWN, lastUsedAt = clock()))
            return
        }
        try {
            // observeQuota records its own outcome; keep the original swallow semantics.
            observeQuota(accountId, connectorFor(account.provider), tokens)
        } catch (e: ProviderException) {
            if (e.code != ProviderErrorCode.AUTH_EXPIRED) {
                // Failed observation = the data did not get fresher. A STALE
                // account (specs/05 §1) stays stale — it must not flip back to
                // unknown, or the quota-stale notice would re-arm forever.
                accountDao.update(
                    account.copy(
                        quotaStatus = if (account.quotaStatus == QuotaStatus.STALE) {
                            QuotaStatus.STALE
                        } else {
                            QuotaStatus.UNKNOWN
                        },
                        lastUsedAt = clock(),
                    ),
                )
            } else {
                throw e
            }
        }
    }

    /** User-driven disconnect (specs/04 §4): tokens wiped, chunk objects stay in the cloud. */
    suspend fun disconnect(accountId: String) {
        val account = account(accountId)
        accountDao.update(
            account.copy(
                encryptedTokens = null,
                tokenExpiresAt = 0,
                status = ProviderAccountStatus.DISCONNECTED,
            ),
        )
        audit(AuditEventType.ACCOUNT_DISCONNECTED, accountId)
    }

    /**
     * Non-swallowing quota probe for the diagnostics "test connection"
     * (specs/06 §3.4): refreshes tokens when needed, reads the quota and stores
     * the observation exactly like [refreshQuota] — but unlike it, the failure
     * propagates so the caller can show the exact outcome. The health ledger
     * records the call either way (specs/06 §2.1).
     */
    suspend fun probeQuota(accountId: String) {
        val account = account(accountId)
        val tokens = getValidTokens(accountId)
        val now = clock()
        try {
            val quota = connectorFor(account.provider).readQuota(tokens)
                ?: throw ProviderException(ProviderErrorCode.QUOTA_UNKNOWN)
            health?.recordSuccess(accountId, kind = "quota")
            accountDao.update(
                account.copy(
                    quotaTotalBytes = quota.totalBytes,
                    quotaUsedBytes = quota.usedBytes,
                    quotaUnlimited = if (quota.unlimited) 1 else 0,
                    quotaStatus = QuotaStatus.CURRENT,
                    quotaObservedAt = now,
                ),
            )
        } catch (e: ProviderException) {
            health?.recordFailure(accountId, e.code, kind = "quota")
            throw e
        }
    }

    private suspend fun observeQuota(accountId: String, connector: ProviderConnector, tokens: TokenPair) {
        val account = account(accountId)
        val now = clock()
        try {
            val quota = connector.readQuota(tokens)
            if (quota == null) {
                health?.recordFailure(accountId, ProviderErrorCode.QUOTA_UNKNOWN, kind = "quota")
                accountDao.update(account.copy(quotaStatus = QuotaStatus.UNKNOWN, quotaObservedAt = now))
                return
            }
            health?.recordSuccess(accountId, kind = "quota")
            accountDao.update(
                account.copy(
                    quotaTotalBytes = quota.totalBytes,
                    quotaUsedBytes = quota.usedBytes,
                    quotaUnlimited = if (quota.unlimited) 1 else 0,
                    quotaStatus = QuotaStatus.CURRENT,
                    quotaObservedAt = now,
                ),
            )
        } catch (e: ProviderException) {
            health?.recordFailure(accountId, e.code, kind = "quota")
            if (e.code == ProviderErrorCode.QUOTA_UNKNOWN) {
                accountDao.update(account.copy(quotaStatus = QuotaStatus.UNKNOWN, quotaObservedAt = now))
            } else {
                throw e
            }
        }
    }

    private suspend fun account(accountId: String): ProviderAccountEntity =
        requireNotNull(accountDao.getById(accountId)) { "account not found: $accountId" }

    private suspend fun audit(type: String, accountId: String) {
        db.auditEventDao().insert(
            AuditEventEntity(
                id = UUID.randomUUID().toString(),
                type = type,
                detailJson = "{\"account_id\":\"$accountId\"}",
                createdAt = clock(),
            ),
        )
    }

    private companion object {
        const val REFRESH_MARGIN_MS = 5 * 60 * 1000L
    }
}
