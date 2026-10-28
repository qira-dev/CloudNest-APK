package com.qiradev.cloudnest.engine.providers

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * One-time OAuth state values (specs/04 §2): random, valid for 2 minutes,
 * consumed on first use. CSRF guard for the redirect back into the app.
 */
class OAuthStateStore(private val clock: () -> Long = System::currentTimeMillis) {

    data class State(val value: String, val provider: String, val expiresAtMs: Long)

    private val states = ConcurrentHashMap<String, State>()

    fun newOauthState(provider: String): String {
        val bytes = ByteArray(STATE_BYTES)
        SECURE_RANDOM.nextBytes(bytes)
        val value = bytes.joinToString("") { "%02x".format(it) }
        states[value] = State(value, provider, clock() + TTL_MS)
        return value
    }

    /**
     * Returns the provider the state was issued for, or NULL when the state is
     * unknown, expired, or already consumed.
     */
    fun validateAndConsume(state: String): String? {
        val entry = states.remove(state) ?: return null
        return if (clock() <= entry.expiresAtMs) entry.provider else null
    }

    private companion object {
        const val TTL_MS = 2 * 60 * 1000L
        const val STATE_BYTES = 16
        val SECURE_RANDOM = SecureRandom()
    }
}
