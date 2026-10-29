package com.qiradev.cloudnest.engine.providers

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Shared network plumbing for the OAuth REST connectors (specs/04 §6: OkHttp,
 * 30s timeouts, safe error codes only). Every failure maps to the §5 codes —
 * provider internals and tokens never reach callers.
 */
internal object ProviderHttp {

    const val TIMEOUT_S = 30L

    fun asOkHttp(callFactory: Call.Factory?): OkHttpClient = (callFactory as? OkHttpClient)
        ?: OkHttpClient.Builder()
            .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()

    /** Raw status + body, [IOException] mapped to provider_down. */
    fun raw(http: OkHttpClient, request: Request): Pair<Int, String> = try {
        http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
    } catch (e: IOException) {
        throw ProviderException(ProviderErrorCode.PROVIDER_DOWN, e)
    }

    /** Raw status + bytes, [IOException] mapped to provider_down. */
    fun rawBytes(http: OkHttpClient, request: Request): Pair<Int, ByteArray> = try {
        http.newCall(request).execute().use { response ->
            response.code to (response.body?.bytes() ?: ByteArray(0))
        }
    } catch (e: IOException) {
        throw ProviderException(ProviderErrorCode.PROVIDER_DOWN, e)
    }

    /** [raw] plus §5 status mapping for non-2xx responses. */
    fun checked(http: OkHttpClient, request: Request, isAuthError: (body: String) -> Boolean = { false }): String {
        val (code, body) = raw(http, request)
        checkStatus(code, body, isAuthError)
        return body
    }

    /**
     * §5 error mapping shared by the connectors: 401 → auth_expired,
     * 429 → rate limited, 5xx → provider down, other 4xx → quota unknown.
     */
    fun checkStatus(code: Int, body: String, isAuthError: (body: String) -> Boolean = { false }) {
        when {
            code in 200..299 -> Unit
            code == 401 -> throw ProviderException(ProviderErrorCode.AUTH_EXPIRED)
            code == 429 -> throw ProviderException(ProviderErrorCode.PROVIDER_RATE_LIMITED)
            code in 500..599 -> throw ProviderException(ProviderErrorCode.PROVIDER_DOWN)
            code in 400..499 && isAuthError(body) -> throw ProviderException(ProviderErrorCode.AUTH_EXPIRED)
            else -> throw ProviderException(ProviderErrorCode.QUOTA_UNKNOWN)
        }
    }
}
