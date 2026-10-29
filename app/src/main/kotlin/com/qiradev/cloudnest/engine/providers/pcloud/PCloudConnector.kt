package com.qiradev.cloudnest.engine.providers.pcloud

import android.net.Uri
import com.qiradev.cloudnest.engine.providers.OAuthStateStore
import com.qiradev.cloudnest.engine.providers.Pkce
import com.qiradev.cloudnest.engine.providers.ProviderConnector
import com.qiradev.cloudnest.engine.providers.ProviderErrorCode
import com.qiradev.cloudnest.engine.providers.ProviderException
import com.qiradev.cloudnest.engine.providers.ProviderHttp
import com.qiradev.cloudnest.engine.providers.Quota
import com.qiradev.cloudnest.engine.providers.TokenPair
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Built-in pCloud config (specs/01 §2). The client id stays empty until the
 * owner registers the pCloud app — the connect flow fails honestly until
 * then. OAuth 2.1 code+PKCE per specs/04 §3.4; the redirect is the app's own
 * scheme (`cloudnest://oauth`).
 */
data class PCloudConfig(
    val clientId: String,
    val redirectUri: String,
    val rootFolderName: String = "CloudNest Data",
    val authorizeUrl: String = "https://my.pcloud.com/oauth2/authorize",
    val tokenUrl: String = "https://my.pcloud.com/oauth2/token",
    val apiBase: String = "https://api.pcloud.com",
)

/**
 * pCloud implementation of the provider contract (specs/04 §3.4) over the
 * documented folder-id/file-id API. pCloud answers HTTP 200 with a JSON
 * `result` code — 0 is success, everything else is mapped to the §5 codes.
 */
class PCloudConnector(
    private val config: PCloudConfig,
    private val flowStore: OAuthStateStore? = null,
    callFactory: Call.Factory? = null,
) : ProviderConnector {

    override val id: String = "pcloud"

    private val http: OkHttpClient = ProviderHttp.asOkHttp(callFactory)

    override suspend fun buildAuthUrl(state: String): Uri {
        require(config.clientId.isNotBlank()) { "pCloud client id is not configured yet (specs/04 §3.4)" }
        val verifier = Pkce.newVerifier()
        flowStore?.saveCodeVerifier(id, verifier)
        return Uri.parse(config.authorizeUrl).buildUpon()
            .appendQueryParameter("client_id", config.clientId)
            .appendQueryParameter("redirect_uri", config.redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", Pkce.challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
    }

    override suspend fun exchangeCode(code: String): TokenPair {
        val verifier = flowStore?.takeCodeVerifier(id)
        val form = FormBody.Builder()
            .add("client_id", config.clientId)
            .add("redirect_uri", config.redirectUri)
            .add("grant_type", "authorization_code")
            .add("code", code)
        verifier?.let { form.add("code_verifier", it) }
        return tokenRequest(form.build())
    }

    override suspend fun refreshToken(refreshToken: String): TokenPair {
        val form = FormBody.Builder()
            .add("client_id", config.clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        return tokenRequest(form)
    }

    override suspend fun readQuota(tokens: TokenPair): Quota? {
        val info = apiCall(tokens, "userinfo")
        if (!info.has("quota")) return null
        val total = info.optLong("quota", -1L)
        return Quota(
            totalBytes = total.takeIf { it > 0 },
            usedBytes = info.optLong("usedquota", -1L).takeIf { it >= 0 },
            // pCloud signals "no quota limit" (lifetime/premium plans) with quota = 0.
            unlimited = total == 0L,
        )
    }

    override suspend fun ensureRoot(tokens: TokenPair): String {
        val name = config.rootFolderName
        val created = runCatching { apiCall(tokens, "createfolder", "folderid" to "0", "name" to name) }
            .getOrNull()
        created?.optJSONObject("metadata")?.let { return it.getLong("folderid").toString() }

        // Name already taken (or any create error): find the folder by listing the root.
        val root = apiCall(tokens, "listfolder", "folderid" to "0")
        val children = root.optJSONObject("metadata")?.optJSONArray("content") ?: JSONArray()
        for (i in 0 until children.length()) {
            val child = children.getJSONObject(i)
            if (child.optString("name") == name && child.optLong("folderid", -1L) > 0) {
                return child.getLong("folderid").toString()
            }
        }
        created?.let { throw apiError(it.optLong("result", -1L)) }
        throw ProviderException(ProviderErrorCode.QUOTA_UNKNOWN)
    }

    override suspend fun uploadChunk(tokens: TokenPair, root: String, name: String, bytes: ByteArray): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("folderid", root)
            .addFormDataPart("filename", name)
            .addFormDataPart("nopartial", "1")
            .addFormDataPart(
                "file",
                name,
                bytes.toRequestBody(OCTET_STREAM_MEDIA),
            )
            .build()
        val response = apiCall(tokens, "uploadfile", body = body)
        val metadata = response.optJSONArray("metadata")
            ?: throw ProviderException(ProviderErrorCode.QUOTA_UNKNOWN)
        require(metadata.length() > 0) { "empty uploadfile response" }
        return metadata.getJSONObject(0).getLong("fileid").toString()
    }

    override suspend fun downloadChunk(tokens: TokenPair, remoteId: String): ByteArray {
        val linkResponse = apiCall(tokens, "getfilelink", "fileid" to remoteId, "forcedownload" to "1")
        val link = linkResponse.optString("link").takeIf { it.isNotEmpty() }
            ?: "https://${linkResponse.optString("hostname")}${linkResponse.optString("path")}"
        val (code, bytes) = ProviderHttp.rawBytes(http, Request.Builder().url(link).get().build())
        ProviderHttp.checkStatus(code, String(bytes, Charsets.ISO_8859_1))
        return bytes
    }

    override suspend fun deleteObject(tokens: TokenPair, remoteId: String) {
        // 2002 "File not found" → already gone — purge retries stay idempotent.
        apiCall(tokens, "deletefile", "fileid" to remoteId, ignoreResults = setOf(2002))
    }

    // ---- plumbing ----

    private fun apiCall(
        tokens: TokenPair,
        method: String,
        vararg params: Pair<String, String>,
        body: okhttp3.RequestBody? = null,
        ignoreResults: Set<Long> = emptySet(),
    ): JSONObject {
        val url = buildString {
            append(config.apiBase).append('/').append(method)
            append("?access_token=").append(tokens.accessToken)
            params.forEach { (key, value) ->
                append('&').append(key).append('=')
                append(java.net.URLEncoder.encode(value, "UTF-8"))
            }
        }
        val (code, text) = ProviderHttp.raw(
            http,
            (if (body != null) Request.Builder().url(url).post(body) else Request.Builder().url(url).get()).build(),
        )
        ProviderHttp.checkStatus(code, text)
        val json = JSONObject(text)
        val result = json.optLong("result", 0L)
        if (result != 0L && result !in ignoreResults) {
            throw apiError(result)
        }
        return json
    }

    /** pCloud `result` codes → safe §5 codes; the provider text never leaves this class. */
    private fun apiError(result: Long): ProviderException = when (result) {
        1000L, 2092L -> ProviderException(ProviderErrorCode.AUTH_EXPIRED)
        4290L -> ProviderException(ProviderErrorCode.PROVIDER_RATE_LIMITED)
        else -> ProviderException(ProviderErrorCode.QUOTA_UNKNOWN)
    }

    private fun tokenRequest(form: FormBody): TokenPair {
        val json = JSONObject(
            ProviderHttp.checked(
                http,
                Request.Builder().url(config.tokenUrl).post(form).build(),
                isAuthError = { it.contains("invalid_grant") || it.contains("\"result\":1000") },
            ),
        )
        return TokenPair(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() },
            // pCloud may omit expires_in — long-lived token, refresh margin never fires.
            expiresAtMs = System.currentTimeMillis() +
                json.optLong("expires_in", DEFAULT_EXPIRES_IN_S) * 1000,
        )
    }

    private companion object {
        val OCTET_STREAM_MEDIA = "application/octet-stream".toMediaType()
        const val DEFAULT_EXPIRES_IN_S = 10L * 365 * 24 * 60 * 60
    }
}
