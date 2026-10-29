package com.qiradev.cloudnest.engine.providers

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (RFC 7636) helpers — specs/04 §3.2/§3.4 require code+PKCE for the
 * Dropbox, OneDrive and pCloud flows. The verifier stays on the device (in
 * [OAuthStateStore]); only the challenge travels in the authorize URL.
 */
internal object Pkce {

    fun newVerifier(): String {
        val bytes = ByteArray(VERIFIER_BYTES)
        SECURE_RANDOM.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private const val VERIFIER_BYTES = 32
    private val SECURE_RANDOM = SecureRandom()
}
