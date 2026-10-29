package com.qiradev.cloudnest.engine.security

import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.SettingsKeys
import com.qiradev.cloudnest.data.entity.SettingsEntity
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Outcome of an unlock attempt (specs/08 §2). */
sealed interface UnlockResult {
    data object Unlocked : UnlockResult
    data class WrongPassword(val attemptsLeft: Int) : UnlockResult
    data class LockedOut(val retryAfterMs: Long) : UnlockResult
    data object NotConfigured : UnlockResult
}

/**
 * Unlock setup/verification (specs/08 §2): the password itself is never stored —
 * a PBKDF2-SHA256 hash (≥200k iterations, random salt) lands in
 * `settings.unlock_hash`. Five consecutive wrong answers require a 30-second
 * wait. The lockout counter is in-memory (a restart clears it): the spec fixes
 * the 30-second wait, not persistence, and no non-spec settings key is added.
 */
class UnlockManager(
    private val db: CloudNestDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val iterations: Int = PBKDF2_ITERATIONS,
) {

    private val lock = Mutex()
    private var failedAttempts = 0
    private var lockedUntil = 0L

    suspend fun isConfigured(): Boolean =
        db.settingsDao().getValue(SettingsKeys.UNLOCK_HASH) != null

    /** First-run setup and later "change unlock" both go through this overwrite. */
    suspend fun setUnlockPassword(password: String) {
        require(password.isNotEmpty()) { "unlock password must not be empty" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(password, salt, iterations)
        val encoded = listOf("pbkdf2_sha256", iterations.toString(), b64(salt), b64(hash)).joinToString("$")
        db.settingsDao().upsert(SettingsEntity(SettingsKeys.UNLOCK_HASH, encoded))
    }

    suspend fun verify(password: String): UnlockResult {
        val stored = db.settingsDao().getValue(SettingsKeys.UNLOCK_HASH)
            ?: return UnlockResult.NotConfigured
        return lock.withLock {
            val now = clock()
            if (now < lockedUntil) {
                return UnlockResult.LockedOut(lockedUntil - now)
            }
            val parts = stored.split("$")
            val storedIterations = parts.getOrNull(1)?.toIntOrNull() ?: iterations
            val salt = parts.getOrNull(2)?.let { fromB64(it) }
            val expected = parts.getOrNull(3)?.let { fromB64(it) }
            val matches = salt != null && expected != null &&
                MessageDigest.isEqual(pbkdf2(password, salt, storedIterations), expected)
            if (matches) {
                failedAttempts = 0
                UnlockResult.Unlocked
            } else {
                failedAttempts++
                if (failedAttempts >= MAX_ATTEMPTS) {
                    lockedUntil = now + LOCKOUT_MS
                    failedAttempts = 0
                    UnlockResult.LockedOut(LOCKOUT_MS)
                } else {
                    UnlockResult.WrongPassword(MAX_ATTEMPTS - failedAttempts)
                }
            }
        }
    }

    /** Seconds left on the active lockout, for the unlock screen. */
    fun retryAfterMs(): Long = (lockedUntil - clock()).coerceAtLeast(0L)

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS))
            .encoded

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun fromB64(value: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (e: IllegalArgumentException) {
        throw IllegalStateException("corrupt unlock hash", e)
    }

    companion object {
        /** specs/08 §2: ≥200k rounds — the floor plus a margin. */
        const val PBKDF2_ITERATIONS = 210_000
        const val HASH_BITS = 256
        const val SALT_BYTES = 16
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MS = 30_000L
    }
}
