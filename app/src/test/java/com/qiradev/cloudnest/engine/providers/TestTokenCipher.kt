package com.qiradev.cloudnest.engine.providers

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Unit-test stand-in for [AesGcmKeystoreCipher]: a real AES-256-GCM round trip
 * with a process-local key (no AndroidKeyStore in the JVM). Mirrors the
 * production storage format [version][iv][ciphertext].
 */
class TestTokenCipher : TokenCipher {

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > 13 && blob[0] == VERSION) { "unknown token blob format" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.encoded, "AES"), GCMParameterSpec(128, blob, 1, 12))
        return cipher.doFinal(blob, 13, blob.size - 13)
    }

    private companion object {
        const val VERSION: Byte = 1
    }
}
