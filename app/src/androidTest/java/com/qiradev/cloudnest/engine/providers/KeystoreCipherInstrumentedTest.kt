package com.qiradev.cloudnest.engine.providers

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * specs/10 SecurityTest (instrumented tier) — the real AndroidKeyStore AES-256-GCM
 * cipher round-trip; tokens must survive serialize→restore and never appear in
 * the stored blob. Runs on a device/emulator only.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreCipherInstrumentedTest {

    @Test
    fun keystoreCipherRoundTripsTokens() {
        // Unique alias per run so repeat executions never collide.
        val alias = "cloudnest-test-${System.nanoTime()}"
        try {
            val cipher = AesGcmKeystoreCipher(alias)
            val store = TokenStore(cipher)

            val blob = store.serialize("access-token-value", "refresh-token-value", 123_456_789L)
            val blobText = String(blob, Charsets.ISO_8859_1)
            assertFalse("access-token-value" in blobText)
            assertFalse("refresh-token-value" in blobText)
            assertTrue(blob.size > 13)

            val restored = store.restore(blob)
            assertArrayEquals("access-token-value".toByteArray(), restored.accessToken.toByteArray())
            assertEquals("refresh-token-value", restored.refreshToken)
            assertEquals(123_456_789L, restored.expiresAtMs)

            // Tampering with the ciphertext must fail the GCM tag check.
            val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
            var tamperRejected = false
            try {
                store.restore(tampered)
            } catch (expected: Exception) {
                tamperRejected = true
            }
            assertTrue(tamperRejected)
        } finally {
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(alias)
        }
    }
}
