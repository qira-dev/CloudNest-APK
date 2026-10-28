package com.qiradev.cloudnest.engine.providers

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Token persistence (specs/04 §1+§2, specs/08): tokens are encrypted with an
 * AES-256-GCM key that lives in AndroidKeyStore and never leaves the device.
 * Storage format: [1-byte version][12-byte IV][ciphertext].
 */
interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

class AesGcmKeystoreCipher(private val keyAlias: String = DEFAULT_KEY_ALIAS) : TokenCipher {

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        require(iv.size == IV_LENGTH) { "unexpected IV length: ${iv.size}" }
        return byteArrayOf(VERSION) + iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > 1 + IV_LENGTH && blob[0] == VERSION) { "unknown token blob format" }
        val iv = blob.copyOfRange(1, 1 + IV_LENGTH)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(blob, 1 + IV_LENGTH, blob.size - 1 - IV_LENGTH)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_KEY_ALIAS = "cloudnest-token-key"
        const val VERSION: Byte = 1
        const val IV_LENGTH = 12
    }
}

/**
 * Encrypts/decrypts the token JSON stored in `provider_accounts.encrypted_tokens`.
 * The plaintext never touches disk in any form.
 */
class TokenStore(private val cipher: TokenCipher) {

    fun serialize(accessToken: String, refreshToken: String?, expiresAtMs: Long): ByteArray =
        cipher.encrypt(buildString {
            append("{\"access_token\":")
            append(jsonString(accessToken))
            append(",\"refresh_token\":")
            append(jsonString(refreshToken))
            append(",\"expires_at\":")
            append(expiresAtMs)
            append("}")
        }.toByteArray(Charsets.UTF_8))

    fun restore(blob: ByteArray): StoredTokens = parser.parse(String(cipher.decrypt(blob), Charsets.UTF_8))

    data class StoredTokens(val accessToken: String, val refreshToken: String?, val expiresAtMs: Long)

    private fun jsonString(value: String?): String {
        if (value == null) return "null"
        return buildString {
            append('"')
            value.forEach { ch ->
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
            append('"')
        }
    }

    private val parser = StoredTokensParser()
}

private class StoredTokensParser {
    /** Minimal parser for the fixed key order written by [TokenStore.serialize]. */
    fun parse(json: String): TokenStore.StoredTokens {
        fun stringField(name: String): String? =
            Regex("\"$name\":(\"(?:[^\"\\\\]|\\\\.)*\"|null)").find(json)?.groupValues?.get(1)
                ?.removeSurrounding("\"")
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
        fun longField(name: String): Long =
            Regex("\"$name\":(-?\\d+)").find(json)?.groupValues?.get(1)?.toLong()
                ?: throw IllegalStateException("token JSON missing $name")
        return TokenStore.StoredTokens(
            accessToken = stringField("access_token") ?: throw IllegalStateException("token JSON missing access_token"),
            refreshToken = stringField("refresh_token"),
            expiresAtMs = longField("expires_at"),
        )
    }
}
