package com.qiradev.cloudnest.engine.providers.onedrive

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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Built-in OneDrive config (specs/01 §2). The client id stays empty until the
 * owner registers the Microsoft app — the connect flow fails honestly until
 * then. Public-client code+PKCE on the v2.0 endpoints per specs/04 §3.3; the
 * redirect is the app's own scheme (`cloudnest://oauth`) registered in Azure.
 */
data class OneDriveConfig(
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String> = DEFAULT_SCOPES,
    val rootFolderName: String = "CloudNest Data",
    val authorizeUrl: String = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
    val tokenUrl: String = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
    val graphBase: String = "https://graph.microsoft.com/v1.0/",
) {
    companion object {
        /** offline_access is required for refresh tokens on the v2.0 endpoints. */
        val DEFAULT_SCOPES = listOf("Files.ReadWrite", "User.Read", "offline_access")
    }
}

/**
 * OneDrive implementation of the provider contract (specs/04 §3.3) over the
 * documented Graph v1.0 surface. Simple upload (≤250 MB per spec) covers the
 * ≤64 MiB chunks; handles are Graph item ids.
 */
class OneDriveConnector(
    private val config: OneDriveConfig,
    private val flowStore: OAuthStateStore? = null,
    callFactory: Call.Factory? = null,
) : ProviderConnector {

    override val id: String = "onedrive"

    private val http: OkHttpClient = ProviderHttp.asOkHttp(callFactory)

    override suspend fun buildAuthUrl(state: String): Uri {
        require(config.clientId.isNotBlank()) { "OneDrive client id is not configured yet (specs/04 §3.3)" }
        val verifier = Pkce.newVerifier()
        flowStore?.saveCodeVerifier(id, verifier)
        return Uri.parse(config.authorizeUrl).buildUpon()
            .appendQueryParameter("client_id", config.clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", config.redirectUri)
            .appendQueryParameter("response_mode", "query")
            .appendQueryParameter("scope", config.scopes.joinToString(" "))
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", Pkce.challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
    }

    override suspend fun exchangeCode(code: String): TokenPair {
        val verifier = flowStore?.takeCodeVerifier(id)
        val form = FormBody.Builder()
            .add("client_id", config.clientId)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", config.redirectUri)
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
        val drive = JSONObject(ProviderHttp.checked(http, authorized(tokens).url(config.graphBase + "me/drive").get().build()))
        val quota = drive.optJSONObject("quota") ?: return null
        return Quota(
            totalBytes = quota.optLong("total", -1L).takeIf { it >= 0 },
            usedBytes = quota.optLong("used", -1L).takeIf { it >= 0 },
            unlimited = false,
        )
    }

    override suspend fun ensureRoot(tokens: TokenPair): String {
        val children = JSONObject(
            ProviderHttp.checked(http, authorized(tokens).url(config.graphBase + "me/drive/root/children").get().build()),
        )
        children.optJSONArray("value")?.let { items ->
            findByName(items, config.rootFolderName)?.let { return it }
        }
        // Create it; on a name race re-list instead of failing the connect.
        val payload = JSONObject()
            .put("name", config.rootFolderName)
            .put("folder", JSONObject())
            .put("@microsoft.graph.conflictBehavior", "fail")
            .toString()
        val create = ProviderHttp.raw(
            http,
            authorized(tokens)
                .url(config.graphBase + "me/drive/root/children")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build(),
        )
        if (create.first in 200..299) return JSONObject(create.second).getString("id")
        val relist = JSONObject(
            ProviderHttp.checked(http, authorized(tokens).url(config.graphBase + "me/drive/root/children").get().build()),
        )
        relist.optJSONArray("value")?.let { findByName(it, config.rootFolderName) }?.let { return it }
        ProviderHttp.checkStatus(create.first, create.second)
        throw ProviderException(ProviderErrorCode.QUOTA_UNKNOWN)
    }

    override suspend fun uploadChunk(tokens: TokenPair, root: String, name: String, bytes: ByteArray): String {
        // Simple upload by path (≤250 MB per specs/04 §3.3) — chunks are ≤64 MiB.
        val itemPath = encodePath("$root/${name.trim('/')}")
        val request = authorized(tokens)
            .url(config.graphBase + "me/drive/root:" + itemPath + ":/content")
            .put(bytes.toRequestBody(OCTET_STREAM_MEDIA))
            .build()
        return JSONObject(ProviderHttp.checked(http, request)).getString("id")
    }

    override suspend fun downloadChunk(tokens: TokenPair, remoteId: String): ByteArray {
        val (code, bytes) = ProviderHttp.rawBytes(
            http,
            authorized(tokens).url(config.graphBase + "me/drive/items/$remoteId/content").get().build(),
        )
        ProviderHttp.checkStatus(code, String(bytes, Charsets.ISO_8859_1))
        return bytes
    }

    override suspend fun deleteObject(tokens: TokenPair, remoteId: String) {
        val (code, body) = ProviderHttp.raw(
            http,
            authorized(tokens).url(config.graphBase + "me/drive/items/$remoteId").delete().build(),
        )
        // Already-gone objects are success — purge retries stay idempotent.
        if (code != 204 && code != 404) ProviderHttp.checkStatus(code, body)
    }

    private fun authorized(tokens: TokenPair): Request.Builder =
        Request.Builder().header("Authorization", "Bearer ${tokens.accessToken}")

    private fun tokenRequest(form: FormBody): TokenPair {
        val json = JSONObject(
            ProviderHttp.checked(
                http,
                Request.Builder().url(config.tokenUrl).post(form).build(),
                isAuthError = { it.contains("invalid_grant") || it.contains("interaction_required") },
            ),
        )
        return TokenPair(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() },
            expiresAtMs = System.currentTimeMillis() + json.getLong("expires_in") * 1000,
        )
    }

    private fun findByName(items: JSONArray, name: String): String? {
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.optString("name") == name && item.has("folder")) return item.getString("id")
        }
        return null
    }

    /** Graph item-path syntax: "/segment/segment", each segment percent-encoded. */
    private fun encodePath(path: String): String =
        "/" + path.split('/').filter { it.isNotEmpty() }
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val OCTET_STREAM_MEDIA = "application/octet-stream".toMediaType()
    }
}
