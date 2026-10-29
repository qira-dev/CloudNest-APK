package com.qiradev.cloudnest.ui

import android.content.Intent
import android.net.Uri

/**
 * Parsed OAuth redirect (specs/04 §2): the provider sends the user back to
 * `cloudnest://oauth?code=…&state=…`; MainActivity forwards it here and the
 * app shell completes the connection through [AccountConnectionManager].
 */
data class CloudNestOAuth(val code: String, val state: String) {

    companion object {
        fun fromIntent(intent: Intent?): CloudNestOAuth? {
            val data: Uri = intent?.data ?: return null
            if (data.scheme != "cloudnest" || data.host != "oauth") return null
            val code = data.getQueryParameter("code") ?: return null
            val state = data.getQueryParameter("state") ?: return null
            return CloudNestOAuth(code, state)
        }
    }
}
