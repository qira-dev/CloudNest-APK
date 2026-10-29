package com.qiradev.cloudnest.engine.providers.dropbox

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
import org.json.JSONObject

/**
 * Built-in Dropbox config (specs/01 §2: no provider_app_configs table). The
 * app key stays empty until the owner registers the Dropbox app — the connect
 * flow fails honestly until then. The registered redirect is the app's own
 * scheme (`cloudnest://oauth`), used with code+PKCE per specs/04 §3.2.
 */
data class DropboxConfig(
    val clientId: String,
    val redirectUri: String,
    val rootFolderName: String = "CloudNest Data",
    val authorizeUrl: String = "https://www.dropbox.com/oauth2/authorize",
    val tokenUrl: String = "https://api.dropbox.com/oauth2/token",
    val apiBase: String = "https://api.dropboxapi.com/2/",
    val contentBase: String = "https://content.dropboxapi.com/2/",
)

/**
 * Dropbox implementation of the provider contract (specs/04 §3.2). Root and
 * chunk handles are Dropbox paths/ids; chunk objects live under "/CloudNest
 * Data" with the "<placement_id>.bin" naming of specs/04 §1.
 */
class DropboxConnector(
    private val config: DropboxConfig,
    private val flowStore: OAuthStateStore? = null,
    callFactory: Call.Factory? = null,
) : ProviderConnector {

    override val id: String = "dropbox"

    private val http: OkHttpClient = ProviderHttp.asOkHttp(callFactory)

    override suspend fun buildAuthUrl(state: String): Uri {
        require(config.clientId.isNotBlank()) { "Dropbox app key is not configured yet (specs/04 §3.2)" }
        val verifier = Pkce.newVerifier()
        flowStore?.saveCodeVerifier(id, verifier)
        return Uri.parse(config.authorizeUrl).buildUpon()
            .appendQueryParameter("client_id", config.clientId)
            .appendQueryParameter("redirect_uri", config.redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", Pkce.challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("token_access_type", "offline")
            .build()
    }

    override suspend fun exchangeCode(code: String): TokenPair {
        val verifier = flowStore?.takeCodeVerifier(id)
        val form = FormBody.Builder()
            .add("code", code)
            .add("grant_type", "authorization_code")
            .add("client_id", config.clientId)
            .add("redirect_uri", config.redirectUri)
        verifier?.let { form.add("code_verifier", it) }
        return tokenRequest(form.build())
    }

    override suspend fun refreshToken(refreshToken: String): TokenPair {
        val form = FormBody.Builder()
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .add("client_id", config.clientId)
            .build()
        return tokenRequest(form)
    }

    override suspend fun readQuota(tokens: TokenPair): Quota? {
        val body = JSONObject(
            ProviderHttp.checked(http, authorized(tokens).url(config.apiBase + "users/get_space_usage").post(EMPTY_BODY).build()),
        )
        val allocation = body.optJSONObject("allocation") ?: return null
        return Quota(
            totalBytes = allocation.optLong("allocated", -1L).takeIf { it >= 0 },
            usedBytes = body.optLong("used", -1L).takeIf { it >= 0 },
            unlimited = false,
        )
    }

    override suspend fun ensureRoot(tokens: TokenPair): String {
        val path = "/${config.rootFolderName.trim('/')}"
        val payload = JSONObject().put("path", path).put("autorename", false).toString()
        val request = authorized(tokens)
            .url(config.apiBase + "files/create_folder_v2")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        val (code, _) = ProviderHttp.raw(http, request)
        // Conflict means the folder already exists — either way the path is the handle.
        if (code != 200 && code != 409) ProviderHttp.checkStatus(code, "")
        return path
    }

    override suspend fun uploadChunk(tokens: TokenPair, root: String, name: String, bytes: ByteArray): String {
        val arg = JSONObject()
            .put("path", "$root/${name.trim('/')}")
            .put("mode", "add")
            .put("autorename", false)
        val request = authorized(tokens)
            .url(config.contentBase + "files/upload")
            .header("Dropbox-API-Arg", arg.toString())
            .post(bytes.toRequestBody(OCTET_STREAM_MEDIA))
            .build()
        return JSONObject(ProviderHttp.checked(http, request)).getString("id")
    }

    override suspend fun downloadChunk(tokens: TokenPair, remoteId: String): ByteArray {
        val arg = JSONObject().put("path", remoteId)
        val request = authorized(tokens)
            .url(config.contentBase + "files/download")
            .header("Dropbox-API-Arg", arg.toString())
            .post(EMPTY_BODY)
            .build()
        val (code, bytes) = ProviderHttp.rawBytes(http, request)
        if (code == 409) {
            // A copy that is gone server-side behaves like a down account —
            // the fetch layer falls back to the mirror copy (specs/03).
            throw ProviderException(ProviderErrorCode.PROVIDER_DOWN)
        }
        ProviderHttp.checkStatus(code, String(bytes, Charsets.ISO_8859_1))
        return bytes
    }

    override suspend fun deleteObject(tokens: TokenPair, remoteId: String) {
        val payload = JSONObject().put("path", remoteId).toString()
        val request = authorized(tokens)
            .url(config.apiBase + "files/delete_v2")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        val (code, body) = ProviderHttp.raw(http, request)
        // Deleting an already-gone object is success — purge retries stay idempotent.
        if (code != 200 && code != 409) ProviderHttp.checkStatus(code, body)
    }

    private fun authorized(tokens: TokenPair): Request.Builder =
        Request.Builder().header("Authorization", "Bearer ${tokens.accessToken}")

    private fun tokenRequest(form: FormBody): TokenPair {
        val json = JSONObject(
            ProviderHttp.checked(
                http,
                Request.Builder().url(config.tokenUrl).post(form).build(),
                isAuthError = { it.contains("invalid_grant") || it.contains("invalid_access_token") },
            ),
        )
        return TokenPair(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() },
            expiresAtMs = System.currentTimeMillis() + json.getLong("expires_in") * 1000,
        )
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val OCTET_STREAM_MEDIA = "application/octet-stream".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(OCTET_STREAM_MEDIA)
    }
}
