package com.qiradev.cloudnest.engine.providers

import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.entity.MetaEntity
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * One-time OAuth state values (specs/04 §2): random, valid for 2 minutes,
 * consumed on first use. CSRF guard for the redirect back into the app.
 *
 * The store also keeps the PKCE code verifier a connector generates for its
 * pending authorize call (specs/04 §3.2/§3.4: code+PKCE for Dropbox, OneDrive
 * and pCloud). The verifier never leaves the device — only the challenge goes
 * into the authorize URL.
 *
 * Two backings: plain memory (tests) and the `meta` table (production). The
 * production backing matters because the app graph is assembled per
 * composition, and the redirect is completed by a later composition than the
 * one that started the flow — a memory store loses the state in between. On
 * the `meta` table every instance sees the same state set, so the flow
 * survives instance churn (and the browser round trip).
 */
class OAuthStateStore(
    db: CloudNestDatabase? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class State(val value: String, val provider: String, val expiresAtMs: Long)

    private val metaDao = db?.metaDao()
    private val states = ConcurrentHashMap<String, State>()

    suspend fun newOauthState(provider: String): String {
        val bytes = ByteArray(STATE_BYTES)
        SECURE_RANDOM.nextBytes(bytes)
        val value = bytes.joinToString("") { "%02x".format(it) }
        val state = State(value, provider, clock() + TTL_MS)
        if (metaDao == null) {
            states[value] = state
        } else {
            metaDao.upsert(MetaEntity(STATE_PREFIX + value, state.encode()))
        }
        return value
    }

    /**
     * Returns the provider the state was issued for, or NULL when the state is
     * unknown, expired, or already consumed.
     */
    suspend fun validateAndConsume(state: String): String? {
        val entry: State? = if (metaDao == null) {
            states.remove(state)
        } else {
            val raw = metaDao.getValue(STATE_PREFIX + state)
            if (raw != null) metaDao.delete(STATE_PREFIX + state)
            raw?.let(::decode)
        }
        return if (entry != null && clock() <= entry.expiresAtMs) entry.provider else null
    }

    /** Saves the PKCE verifier for the provider's one pending connect (latest wins). */
    suspend fun saveCodeVerifier(provider: String, verifier: String) {
        if (metaDao == null) {
            states[VERIFIER_PREFIX + provider] = State(verifier, provider, Long.MAX_VALUE)
        } else {
            metaDao.upsert(MetaEntity(VERIFIER_PREFIX + provider, verifier))
        }
    }

    /** Takes the pending verifier out — single use, like the state itself. */
    suspend fun takeCodeVerifier(provider: String): String? {
        return if (metaDao == null) {
            states.remove(VERIFIER_PREFIX + provider)?.value
        } else {
            val verifier = metaDao.getValue(VERIFIER_PREFIX + provider)
            if (verifier != null) metaDao.delete(VERIFIER_PREFIX + provider)
            verifier
        }
    }

    private fun State.encode(): String = JSONObject()
        .put("value", value)
        .put("provider", provider)
        .put("expires_at_ms", expiresAtMs)
        .toString()

    private fun decode(raw: String): State? = runCatching {
        val json = JSONObject(raw)
        State(
            value = json.getString("value"),
            provider = json.getString("provider"),
            expiresAtMs = json.getLong("expires_at_ms"),
        )
    }.getOrNull()

    private companion object {
        const val TTL_MS = 2 * 60 * 1000L
        const val STATE_BYTES = 16
        const val STATE_PREFIX = "oauth_state."
        const val VERIFIER_PREFIX = "oauth_pkce."
        val SECURE_RANDOM = SecureRandom()
    }
}
