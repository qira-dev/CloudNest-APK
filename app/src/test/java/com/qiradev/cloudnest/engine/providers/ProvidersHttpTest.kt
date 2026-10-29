package com.qiradev.cloudnest.engine.providers

import android.net.Uri
import com.qiradev.cloudnest.data.buildTestDb
import com.qiradev.cloudnest.engine.providers.dropbox.DropboxConfig
import com.qiradev.cloudnest.engine.providers.dropbox.DropboxConnector
import com.qiradev.cloudnest.engine.providers.onedrive.OneDriveConfig
import com.qiradev.cloudnest.engine.providers.onedrive.OneDriveConnector
import com.qiradev.cloudnest.engine.providers.pcloud.PCloudConfig
import com.qiradev.cloudnest.engine.providers.pcloud.PCloudConnector
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The three OAuth connectors against real-protocol fixtures (specs/04 §3.2–
 * §3.4, §6): authorize URLs, token exchange with PKCE, quota parsing, the
 * chunk contract round trip, idempotent deletes and the §5 error mapping —
 * all replayed documented request/response shapes, no live accounts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProvidersHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var flowStore: OAuthStateStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        flowStore = OAuthStateStore()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- Dropbox (specs/04 §3.2) ----

    private fun dropbox() = DropboxConnector(
        DropboxConfig(
            clientId = "test-app-key",
            redirectUri = "cloudnest://oauth",
            authorizeUrl = server.url("/authorize").toString(),
            tokenUrl = server.url("/token").toString(),
            apiBase = server.url("/api/").toString(),
            contentBase = server.url("/content/").toString(),
        ),
        flowStore = flowStore,
    )

    @Test
    fun dropboxAuthorizeUrlCarriesChallengeAndOfflineTokenType(): Unit = runBlocking {
        val url = dropbox().buildAuthUrl("state-1")
        assertEquals("test-app-key", url.getQueryParameter("client_id"))
        assertEquals("cloudnest://oauth", url.getQueryParameter("redirect_uri"))
        assertEquals("S256", url.getQueryParameter("code_challenge_method"))
        assertEquals("offline", url.getQueryParameter("token_access_type"))
        val challenge = url.getQueryParameter("code_challenge")
        val verifier = flowStore.takeCodeVerifier("dropbox")
        assertNotNull(challenge)
        assertNotNull(verifier)
        assertEquals(challenge, Pkce.challenge(verifier!!))
    }

    @Test
    fun dropboxExchangeSendsVerifierAndParsesTokens(): Unit = runBlocking {
        flowStore.saveCodeVerifier("dropbox", "verifier-1")
        server.enqueue(json("""{"access_token":"at-1","refresh_token":"rt-1","expires_in":14400}"""))
        val tokens = dropbox().exchangeCode("code-1")

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue("code=code-1" in body)
        assertTrue("code_verifier=verifier-1" in body)
        assertTrue("grant_type=authorization_code" in body)
        assertEquals("at-1", tokens.accessToken)
        assertEquals("rt-1", tokens.refreshToken)
    }

    @Test
    fun dropboxQuotaParsesSpaceUsage(): Unit = runBlocking {
        server.enqueue(json("""{"used":100,"allocation":{".tag":"individual","allocated":1000}}"""))
        val quota = dropbox().readQuota(TokenPair("at", null, Long.MAX_VALUE))
        assertEquals(1000L, quota?.totalBytes)
        assertEquals(100L, quota?.usedBytes)
        assertFalse(quota?.unlimited == true)
    }

    @Test
    fun dropboxChunkRoundTripById() = runBlocking {
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        server.enqueue(json("""{"id":"id:abc","name":"x.bin"}"""))
        val remoteId = dropbox().uploadChunk(TokenPair("at", null, Long.MAX_VALUE), "/CloudNest Data", "x.bin", bytes)

        assertEquals("id:abc", remoteId)
        val upload = server.takeRequest()
        // org.json escapes "/" in the header — compare parsed JSON, not raw text.
        val arg = JSONObject(upload.getHeader("Dropbox-API-Arg").orEmpty())
        assertEquals("/CloudNest Data/x.bin", arg.getString("path"))
        assertTrue(upload.body.readByteArray().contentEquals(bytes))

        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val downloaded = dropbox().downloadChunk(TokenPair("at", null, Long.MAX_VALUE), remoteId)
        assertTrue(downloaded.contentEquals(bytes))
        assertEquals(
            "id:abc",
            JSONObject(server.takeRequest().getHeader("Dropbox-API-Arg").orEmpty()).getString("path"),
        )
    }

    @Test
    fun dropboxDeleteIsIdempotentAndMissingDownloadIsProviderDown() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error_summary":"path_lookup/not_found/."}"""))
        dropbox().deleteObject(TokenPair("at", null, Long.MAX_VALUE), "id:gone")

        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error_summary":"path_lookup/not_found/."}"""))
        val e = assertThrows(ProviderException::class.java) {
            runBlocking { dropbox().downloadChunk(TokenPair("at", null, Long.MAX_VALUE), "id:gone") }
        }
        assertEquals(ProviderErrorCode.PROVIDER_DOWN, e.code)
    }

    @Test
    fun dropboxStatusesMapToSafeErrors() = runBlocking {
        val connector = dropbox()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_access_token"}"""))
        assertEquals(ProviderErrorCode.AUTH_EXPIRED, quotaError(connector))
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
        assertEquals(ProviderErrorCode.PROVIDER_RATE_LIMITED, quotaError(connector))
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertEquals(ProviderErrorCode.PROVIDER_DOWN, quotaError(connector))
    }

    @Test
    fun dropboxFullConnectFlowThroughManager(): Unit = runBlocking {
        server.enqueue(json("""{"access_token":"at-flow","refresh_token":"rt-flow","expires_in":14400}"""))
        server.enqueue(json("""{"used":100,"allocation":{".tag":"individual","allocated":1000}}"""))
        server.enqueue(json("""{"metadata":{"id":"id:root","path_display":"/CloudNest Data"}}"""))

        val db = buildTestDb()
        val manager = AccountConnectionManager(
            db = db,
            tokenStore = TokenStore(StaticTestCipher.aesGcm()),
            connectors = mapOf("dropbox" to dropbox()),
            stateStore = flowStore,
        )
        val state = flowStore.newOauthState("dropbox")
        val accountId = manager.completeConnection("code-flow", state)

        val account = db.providerAccountDao().getById(accountId)!!
        assertEquals("connected", account.status)
        assertEquals("/CloudNest Data", account.rootPath)
        assertEquals(1000L, account.quotaTotalBytes)
        val blob = String(account.encryptedTokens!!, Charsets.ISO_8859_1)
        assertFalse("at-flow" in blob)
        assertFalse("rt-flow" in blob)
    }

    // ---- OneDrive (specs/04 §3.3) ----

    private fun onedrive() = OneDriveConnector(
        OneDriveConfig(
            clientId = "test-client-id",
            redirectUri = "cloudnest://oauth",
            authorizeUrl = server.url("/authorize").toString(),
            tokenUrl = server.url("/token").toString(),
            graphBase = server.url("/graph/").toString(),
        ),
        flowStore = flowStore,
    )

    @Test
    fun onedriveAuthorizeUrlRequestsOfflineAccess(): Unit = runBlocking {
        val url = onedrive().buildAuthUrl("state-1")
        assertTrue(url.getQueryParameter("scope").orEmpty().contains("offline_access"))
        assertTrue(url.getQueryParameter("scope").orEmpty().contains("Files.ReadWrite"))
        assertNotNull(url.getQueryParameter("code_challenge"))
        assertNotNull(flowStore.takeCodeVerifier("onedrive"))
    }

    @Test
    fun onedriveRefreshParsesTokens(): Unit = runBlocking {
        server.enqueue(json("""{"access_token":"at-2","refresh_token":"rt-2","expires_in":3600}"""))
        val tokens = onedrive().refreshToken("rt-old")
        assertEquals("at-2", tokens.accessToken)
        assertEquals("rt-2", tokens.refreshToken)
        val body = server.takeRequest().body.readUtf8()
        assertTrue("grant_type=refresh_token" in body)
        assertTrue("refresh_token=rt-old" in body)
    }

    @Test
    fun onedriveQuotaParsesDriveResource(): Unit = runBlocking {
        server.enqueue(json("""{"id":"drive-1","quota":{"total":1000,"used":100,"state":"normal"}}"""))
        val quota = onedrive().readQuota(TokenPair("at", null, Long.MAX_VALUE))
        assertEquals(1000L, quota?.totalBytes)
        assertEquals(100L, quota?.usedBytes)
    }

    @Test
    fun onedriveEnsureRootFindsThenCreates(): Unit = runBlocking {
        val tokens = TokenPair("at", null, Long.MAX_VALUE)
        server.enqueue(json("""{"value":[{"name":"Other","id":"i1","folder":{}},{"name":"CloudNest Data","id":"i2","folder":{}}]}"""))
        assertEquals("i2", onedrive().ensureRoot(tokens))

        server.enqueue(json("""{"value":[]}"""))
        server.enqueue(json("""{"id":"i3","name":"CloudNest Data","folder":{}}"""))
        assertEquals("i3", onedrive().ensureRoot(tokens))
    }

    @Test
    fun onedriveUploadUsesEncodedItemPath(): Unit = runBlocking {
        server.enqueue(json("""{"id":"item-1"}"""))
        val remoteId = onedrive().uploadChunk(TokenPair("at", null, Long.MAX_VALUE), "/CloudNest Data", "x.bin", byteArrayOf(1, 2, 3))
        assertEquals("item-1", remoteId)
        val path = server.takeRequest().path.orEmpty()
        assertTrue("/me/drive/root:/CloudNest%20Data/x.bin:/content" in path)
    }

    @Test
    fun onedriveDeleteTreatsNotFoundAsSuccess(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"itemNotFound"}}"""))
        onedrive().deleteObject(TokenPair("at", null, Long.MAX_VALUE), "item-1")
    }

    // ---- pCloud (specs/04 §3.4) ----

    private fun pcloud() = PCloudConnector(
        PCloudConfig(
            clientId = "test-pcloud-id",
            redirectUri = "cloudnest://oauth",
            authorizeUrl = server.url("/authorize").toString(),
            tokenUrl = server.url("/token").toString(),
            apiBase = server.url("/api").toString(),
        ),
        flowStore = flowStore,
    )

    @Test
    fun pcloudAuthorizeUrlUsesPkce(): Unit = runBlocking {
        val url = pcloud().buildAuthUrl("state-1")
        assertEquals("S256", url.getQueryParameter("code_challenge_method"))
        assertNotNull(url.getQueryParameter("code_challenge"))
        assertNotNull(flowStore.takeCodeVerifier("pcloud"))
    }

    @Test
    fun pcloudZeroQuotaMeansUnlimited(): Unit = runBlocking {
        server.enqueue(json("""{"result":0,"quota":0,"usedquota":5}"""))
        val quota = pcloud().readQuota(TokenPair("at", null, Long.MAX_VALUE))
        assertTrue(quota?.unlimited == true)
        assertNull(quota?.totalBytes)
        assertEquals(5L, quota?.usedBytes)

        server.enqueue(json("""{"result":0,"quota":1000,"usedquota":100}"""))
        val finite = pcloud().readQuota(TokenPair("at", null, Long.MAX_VALUE))
        assertEquals(1000L, finite?.totalBytes)
        assertFalse(finite?.unlimited == true)
    }

    @Test
    fun pcloudResultCodesMapToSafeErrors(): Unit = runBlocking {
        server.enqueue(json("""{"result":1000,"error":"Login required."}"""))
        assertEquals(ProviderErrorCode.AUTH_EXPIRED, quotaError(pcloud()))
        server.enqueue(json("""{"result":4290,"error":"Too many requests. Slow down."}"""))
        assertEquals(ProviderErrorCode.PROVIDER_RATE_LIMITED, quotaError(pcloud()))
    }

    @Test
    fun pcloudEnsureRootCreatesThenFallsBackToListing(): Unit = runBlocking {
        val tokens = TokenPair("at", null, Long.MAX_VALUE)
        server.enqueue(json("""{"result":0,"metadata":{"folderid":42,"name":"CloudNest Data"}}"""))
        assertEquals("42", pcloud().ensureRoot(tokens))

        server.enqueue(json("""{"result":1004,"error":"File/Folder already exists."}"""))
        server.enqueue(json("""{"result":0,"metadata":{"folderid":0,"content":[{"name":"CloudNest Data","folderid":42,"isfolder":true}]}}"""))
        assertEquals("42", pcloud().ensureRoot(tokens))
    }

    @Test
    fun pcloudUploadReturnsFileIdAndDownloadFollowsLink() = runBlocking {
        val bytes = ByteArray(512) { (it % 97).toByte() }
        server.enqueue(json("""{"result":0,"metadata":[{"fileid":77,"name":"x.bin"}]}"""))
        val remoteId = pcloud().uploadChunk(TokenPair("at", null, Long.MAX_VALUE), "42", "x.bin", bytes)
        assertEquals("77", remoteId)
        val upload = server.takeRequest()
        assertTrue(upload.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data"))
        assertTrue(contains(upload.body.readByteArray(), bytes))

        server.enqueue(json("""{"result":0,"link":"${server.url("/file-link")}"}"""))
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val downloaded = pcloud().downloadChunk(TokenPair("at", null, Long.MAX_VALUE), remoteId)
        assertTrue(downloaded.contentEquals(bytes))
    }

    @Test
    fun pcloudDeleteTreatsMissingFileAsSuccess(): Unit = runBlocking {
        server.enqueue(json("""{"result":2002,"error":"File not found."}"""))
        pcloud().deleteObject(TokenPair("at", null, Long.MAX_VALUE), "77")
    }

    // ---- helpers ----

    private suspend fun quotaError(connector: ProviderConnector): String = try {
        connector.readQuota(TokenPair("at", null, Long.MAX_VALUE))
        "no-error"
    } catch (e: ProviderException) {
        e.code
    }

    private fun jsonArgPath(request: okhttp3.mockwebserver.RecordedRequest): String =
        request.getHeader("Dropbox-API-Arg").orEmpty()

    /** Byte-sequence search — multipart wrappers surround the payload bytes. */
    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.size > haystack.size) return false
        outer@ for (start in 0..haystack.size - needle.size) {
            for (i in needle.indices) {
                if (haystack[start + i] != needle[i]) continue@outer
            }
            return true
        }
        return false
    }

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}

/** Real AES-256-GCM with a test-held key — AndroidKeyStore is not on the JVM. */
private object StaticTestCipher {
    fun aesGcm(): TokenCipher {
        val keyBytes = "cloudnest-test-key-0123456789abc".toByteArray(Charsets.UTF_8)
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
