package com.qiradev.cloudnest.engine.providers.google

import android.net.Uri
import com.qiradev.cloudnest.engine.providers.ProviderConnector
import com.qiradev.cloudnest.engine.providers.ProviderErrorCode
import com.qiradev.cloudnest.engine.providers.ProviderException
import com.qiradev.cloudnest.engine.providers.Quota
import com.qiradev.cloudnest.engine.providers.TokenPair
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Built-in OAuth/API config (specs/01 §2: no provider_app_configs table). */
data class GoogleDriveConfig(
    /**
     * Android-type OAuth client id (SHA-1 + package name registered in the owner's
     * Google Cloud console — specs/04 §3.1). No client secret exists for this type.
     */
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String> = DEFAULT_SCOPES,
    val chunkFolderName: String = "CloudNest Data",
) {
    companion object {
        val DEFAULT_SCOPES = listOf(
            "https://www.googleapis.com/auth/drive.file",
            "https://www.googleapis.com/auth/drive.metadata.readonly",
        )
    }
}

/**
 * Google Drive implementation of the provider contract (specs/04 §3.1) over the
 * documented Drive v3 REST surface. Network failures map to the safe error codes
 * of §5 — nothing provider-internal or token-bearing reaches callers.
 */
class GoogleDriveConnector(
    private val config: GoogleDriveConfig,
    callFactory: Call.Factory? = null,
) : ProviderConnector {

    override val id: String = "google_drive"

    private val http: OkHttpClient = (callFactory as? OkHttpClient)
        ?: OkHttpClient.Builder()
            .callTimeout(NETWORK_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(NETWORK_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

    override suspend fun buildAuthUrl(state: String): Uri {
        require(config.clientId.isNotBlank()) { "OAuth client id is not configured yet (specs/04 §3.1)" }
        return Uri.parse("https://accounts.google.com/o/oauth2/v2/auth").buildUpon()
            .appendQueryParameter("client_id", config.clientId)
            .appendQueryParameter("redirect_uri", config.redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", config.scopes.joinToString(" "))
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .appendQueryParameter("state", state)
            .build()
    }

    override suspend fun exchangeCode(code: String): TokenPair {
        val form = FormBody.Builder()
            .add("code", code)
            .add("client_id", config.clientId)
            .add("redirect_uri", config.redirectUri)
            .add("grant_type", "authorization_code")
            .build()
        return tokenRequest(form)
    }

    override suspend fun refreshToken(refreshToken: String): TokenPair {
        val form = FormBody.Builder()
            .add("refresh_token", refreshToken)
            .add("client_id", config.clientId)
            .add("grant_type", "refresh_token")
            .build()
        return tokenRequest(form)
    }

    override suspend fun readQuota(tokens: TokenPair): Quota? {
        val json = JSONObject(
            execute(authorized(tokens).url(DRIVE_V3 + "about?fields=storageQuota").get().build()),
        )
        val quota = json.optJSONObject("storageQuota") ?: return null
        return Quota(
            totalBytes = quota.optString("limit").toLongOrNull(),
            usedBytes = quota.optString("usage").toLongOrNull(),
            unlimited = !quota.has("limit"),
        )
    }

    override suspend fun ensureRoot(tokens: TokenPair): String {
        val query = "name = '${config.chunkFolderName}' and mimeType = '$FOLDER_MIME' and trashed = false"
        val url = DRIVE_V3 + "files?q=" + URLEncoder.encode(query, "UTF-8") +
            "&fields=" + URLEncoder.encode("files(id,name)", "UTF-8")
        val found = JSONObject(execute(authorized(tokens).url(url).get().build()))
        found.optJSONArray("files")?.let { files ->
            if (files.length() > 0) return files.getJSONObject(0).getString("id")
        }
        val metadata = JSONObject().put("name", config.chunkFolderName).put("mimeType", FOLDER_MIME)
        val created = JSONObject(
            execute(
                authorized(tokens)
                    .url(DRIVE_V3 + "files?fields=id")
                    .post(metadata.toString().toRequestBody(JSON_MEDIA))
                    .build(),
            ),
        )
        return created.getString("id")
    }

    override suspend fun uploadChunk(tokens: TokenPair, root: String, name: String, bytes: ByteArray): String {
        // Drive multipart upload wants multipart/related: metadata part + media part.
        val metadata = JSONObject().put("name", name).put("parents", JSONArray().put(root))
        val boundary = "cloudnest-${System.nanoTime()}"
        val payload = ("" +
            "--$boundary\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n\r\n" +
            metadata +
            "\r\n--$boundary\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Transfer-Encoding: binary\r\n\r\n").toByteArray() +
            bytes +
            "\r\n--$boundary--\r\n".toByteArray()
        val request = authorized(tokens)
            .url(DRIVE_UPLOAD_V3 + "files?uploadType=multipart")
            .post(
                payload.toRequestBody("multipart/related; boundary=$boundary".toMediaType()),
            )
            .build()
        return JSONObject(execute(request)).getString("id")
    }

    override suspend fun downloadChunk(tokens: TokenPair, remoteId: String): ByteArray =
        executeBytes(authorized(tokens).url("$DRIVE_V3/files/$remoteId?alt=media").get().build())

    override suspend fun deleteObject(tokens: TokenPair, remoteId: String) {
        execute(authorized(tokens).url("$DRIVE_V3/files/$remoteId").delete().build())
    }

    // ---- plumbing ----

    private fun authorized(tokens: TokenPair): Request.Builder =
        Request.Builder().header("Authorization", "Bearer ${tokens.accessToken}")

    private fun tokenRequest(form: FormBody): TokenPair {
        val json = JSONObject(execute(Request.Builder().url(OAUTH_TOKEN_URL).post(form).build()))
        return TokenPair(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() },
            expiresAtMs = System.currentTimeMillis() + json.getLong("expires_in") * 1000,
        )
    }

    private fun execute(request: Request): String = try {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            checkStatus(response.code, text)
            text
        }
    } catch (e: IOException) {
        throw ProviderException(ProviderErrorCode.PROVIDER_DOWN, e)
    }

    private fun executeBytes(request: Request): ByteArray = try {
        http.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            checkStatus(response.code, String(bytes, Charsets.ISO_8859_1))
            bytes
        }
    } catch (e: IOException) {
        throw ProviderException(ProviderErrorCode.PROVIDER_DOWN, e)
    }

    /** specs/04 §5 error mapping — only safe codes ever leave this class. */
    private fun checkStatus(code: Int, bodyText: String) {
        when {
            code == 429 -> throw ProviderException(ProviderErrorCode.PROVIDER_RATE_LIMITED)
            code in 500..599 -> throw ProviderException(ProviderErrorCode.PROVIDER_DOWN)
            code in 400..499 -> {
                val errorStatus = runCatching {
                    JSONObject(bodyText).optJSONObject("error")?.optString("status").orEmpty()
                }.getOrDefault("")
                val authProblem = code == 401 || bodyText.contains("invalid_grant") ||
                    errorStatus == "INVALID_CREDENTIALS"
                if (authProblem) throw ProviderException(ProviderErrorCode.AUTH_EXPIRED)
                throw ProviderException(ProviderErrorCode.QUOTA_UNKNOWN)
            }
        }
    }

    private companion object {
        const val NETWORK_TIMEOUT_S = 30L
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val DRIVE_V3 = "https://www.googleapis.com/drive/v3/"
        const val DRIVE_UPLOAD_V3 = "https://www.googleapis.com/upload/drive/v3/"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val OCTET_STREAM_MEDIA = "application/octet-stream".toMediaType()
    }
}
