package com.qiradev.cloudnest.engine.providers

import android.net.Uri
import com.qiradev.cloudnest.data.buildTestDb
import com.qiradev.cloudnest.data.entity.ProviderAccountStatus
import com.qiradev.cloudnest.data.entity.QuotaStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * specs/04 §6 + specs/10 §6 MockProviderTest — state validation, connect flow,
 * token refresh, quota states and the upload/download/delete contract, all on
 * the fake connector with no network and no real accounts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MockProviderTest {

    private lateinit var db: com.qiradev.cloudnest.data.CloudNestDatabase
    private lateinit var connector: FakeConnector
    private lateinit var stateStore: OAuthStateStore
    private lateinit var manager: AccountConnectionManager

    private var nowMs: Long = 1_000_000L

    @Before
    fun setUp(): Unit = runBlocking {
        nowMs = 1_000_000L
        db = buildTestDb()
        connector = FakeConnector()
        stateStore = OAuthStateStore { nowMs }
        manager = AccountConnectionManager(
            db = db,
            tokenStore = TokenStore(TestCiphers.staticAesGcm()),
            connectors = mapOf(connector.id to connector),
            stateStore = stateStore,
            clock = { nowMs },
        )
    }

    @Test
    fun startConnectionIssuesStateAndUrl(): Unit = runBlocking {
        val url = manager.startConnection("google_drive")
        val state = Uri.parse(url).getQueryParameter("state")
        assertNotNull(state)
        assertEquals("google_drive", stateStore.validateAndConsume(state!!))
    }

    @Test
    fun stateIsSingleUseAndExpiresInTwoMinutes(): Unit = runBlocking {
        val state = stateStore.newOauthState("google_drive")
        assertEquals("google_drive", stateStore.validateAndConsume(state))
        assertNull(stateStore.validateAndConsume(state))

        val state2 = stateStore.newOauthState("google_drive")
        nowMs += 2 * 60 * 1000L + 1
        assertNull(stateStore.validateAndConsume(state2))
    }

    @Test
    fun completeConnectionRejectsUnknownState(): Unit = runBlocking {
        assertThrows(ProviderException::class.java) {
            runBlocking { manager.completeConnection("code", "bogus-state") }
        }
        assertTrue(db.providerAccountDao().getAll().isEmpty())
    }

    @Test
    fun completeConnectionStoresEncryptedTokensQuotaAndRoot(): Unit = runBlocking {
        val state = stateStore.newOauthState("google_drive")
        val accountId = manager.completeConnection("auth-code", state)

        val account = db.providerAccountDao().getById(accountId)!!
        assertEquals(ProviderAccountStatus.CONNECTED, account.status)
        assertEquals("google_drive", account.provider)
        assertEquals("fake-root", account.rootPath)
        assertEquals(QuotaStatus.CURRENT, account.quotaStatus)
        assertEquals(1_000L, account.quotaTotalBytes)
        assertEquals(100L, account.quotaUsedBytes)
        assertEquals(0, account.quotaUnlimited)
        assertNotNull(account.encryptedTokens)

        // Tokens never sit on disk unencrypted: the blob must not contain raw tokens.
        val blobText = String(account.encryptedTokens!!, Charsets.ISO_8859_1)
        assertFalse("access-for-auth-code" in blobText)
        assertFalse("refresh-1" in blobText)

        val stored = TokenStore(TestCiphers.staticAesGcm()).restore(account.encryptedTokens!!)
        assertEquals("access-for-auth-code", stored.accessToken)
        assertEquals("refresh-1", stored.refreshToken)

        assertEquals(
            listOf("account_connected"),
            db.auditEventDao().latest(10).map { it.type },
        )
        assertEquals(1, connector.exchangeCount)
        assertEquals(1, connector.ensureRootCount)
    }

    @Test
    fun tokensRefreshOnlyInsideTheFiveMinuteMargin(): Unit = runBlocking {
        val accountId = connect()
        assertEquals(0, connector.refreshCount)
        manager.getValidTokens(accountId)
        assertEquals(0, connector.refreshCount)

        // Expiry lives inside the encrypted blob — rewrite it within the 5-minute margin.
        setExpiry(accountId, nowMs + 4 * 60 * 1000L)

        val tokens = manager.getValidTokens(accountId)
        assertEquals(1, connector.refreshCount)
        assertEquals("access-refreshed-1", tokens.accessToken)
        assertEquals("refresh-1", tokens.refreshToken)
    }

    @Test
    fun failedRefreshMarksAccountError(): Unit = runBlocking {
        val accountId = connect()
        setExpiry(accountId, nowMs + 60_000L)
        connector.refreshException = ProviderException(ProviderErrorCode.AUTH_EXPIRED)

        assertThrows(ProviderException::class.java) {
            runBlocking { manager.getValidTokens(accountId) }
        }
        assertEquals(ProviderAccountStatus.ERROR, db.providerAccountDao().getById(accountId)!!.status)
    }

    @Test
    fun providerDownKeepsAccountConnectedButQuotaUnknown(): Unit = runBlocking {
        val accountId = connect()
        connector.readQuotaException = ProviderException(ProviderErrorCode.PROVIDER_DOWN)

        manager.refreshQuota(accountId)

        val account = db.providerAccountDao().getById(accountId)!!
        assertEquals(ProviderAccountStatus.CONNECTED, account.status)
        assertEquals(QuotaStatus.UNKNOWN, account.quotaStatus)
    }

    @Test
    fun quotaUnknownFailureIsSwallowedAndRecorded(): Unit = runBlocking {
        val accountId = connect()
        connector.readQuotaException = ProviderException(ProviderErrorCode.QUOTA_UNKNOWN)

        manager.refreshQuota(accountId)

        val account = db.providerAccountDao().getById(accountId)!!
        assertEquals(QuotaStatus.UNKNOWN, account.quotaStatus)
    }

    @Test
    fun unlimitedQuotaIsFlagged(): Unit = runBlocking {
        val accountId = connect()
        connector.quota = Quota(totalBytes = null, usedBytes = 123L, unlimited = true)

        manager.refreshQuota(accountId)

        val account = db.providerAccountDao().getById(accountId)!!
        assertEquals(1, account.quotaUnlimited)
        assertNull(account.quotaTotalBytes)
        assertEquals(QuotaStatus.CURRENT, account.quotaStatus)
    }

    @Test
    fun disconnectWipesTokensAndAudits(): Unit = runBlocking {
        val accountId = connect()

        manager.disconnect(accountId)

        val account = db.providerAccountDao().getById(accountId)!!
        assertEquals(ProviderAccountStatus.DISCONNECTED, account.status)
        assertNull(account.encryptedTokens)
        assertTrue(
            "account_disconnected" in db.auditEventDao().latest(10).map { it.type },
        )
    }

    @Test
    fun chunkContractRoundTripWithPlacementNaming(): Unit = runBlocking {
        val accountId = connect()
        val tokens = manager.getValidTokens(accountId)

        // specs/04 §1: cloud chunk file name is always "<placement_id>.bin".
        val placementId = "8f0c9a2e-0000-4000-8000-000000000001"
        val bytes = ByteArray(2048) { (it % 251).toByte() }
        val remoteId = connector.uploadChunk(tokens, "fake-root", "$placementId.bin", bytes)

        assertTrue(bytes.contentEquals(connector.downloadChunk(tokens, remoteId)))
        connector.deleteObject(tokens, remoteId)
        assertFalse("$placementId.bin" in connector.chunks.keys)
        assertTrue(remoteId in connector.deletedRemoteIds)
    }

    private suspend fun connect(): String {
        val state = stateStore.newOauthState("google_drive")
        return manager.completeConnection("auth-code", state)
    }

    private suspend fun setExpiry(accountId: String, expiresAtMs: Long) {
        val store = TokenStore(TestCiphers.staticAesGcm())
        val account = db.providerAccountDao().getById(accountId)!!
        val stored = store.restore(account.encryptedTokens!!)
        db.providerAccountDao().update(
            account.copy(
                encryptedTokens = store.serialize(stored.accessToken, stored.refreshToken, expiresAtMs),
                tokenExpiresAt = expiresAtMs,
            ),
        )
    }
}

private object TestCiphers {
    /** Real AES-256-GCM with a test-held key — AndroidKeyStore is not on the JVM. */
    fun staticAesGcm(): TokenCipher {
        val keyBytes = "cloudnest-test-key-0123456789abc".toByteArray(Charsets.UTF_8) // exactly 32 bytes
        val key = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
        return object : TokenCipher {
            override fun encrypt(plain: ByteArray): ByteArray {
                val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
                return byteArrayOf(1) + iv + cipher.doFinal(plain)
            }

            override fun decrypt(blob: ByteArray): ByteArray {
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    javax.crypto.Cipher.DECRYPT_MODE,
                    key,
                    javax.crypto.spec.GCMParameterSpec(128, blob.copyOfRange(1, 13)),
                )
                return cipher.doFinal(blob, 13, blob.size - 13)
            }
        }
    }
}
