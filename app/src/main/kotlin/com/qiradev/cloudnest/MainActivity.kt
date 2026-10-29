package com.qiradev.cloudnest

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.qiradev.cloudnest.ui.CloudNestApp
import com.qiradev.cloudnest.ui.CloudNestOAuth
import com.qiradev.cloudnest.ui.CloudNestTheme
import com.qiradev.cloudnest.ui.AppLocales
import com.qiradev.cloudnest.data.DatabaseProvider
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    /** Latest OAuth redirect (specs/04 §2) — set at launch and on every re-entry. */
    private val oauthCallback = mutableStateOf<CloudNestOAuth?>(null)

    override fun attachBaseContext(base: Context) {
        // specs/09 §13 — the stored language wraps the whole activity context.
        val tag = runBlocking { AppLocales.savedTag(DatabaseProvider.get(base)) }
        super.attachBaseContext(AppLocales.wrapContext(base, tag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        oauthCallback.value = CloudNestOAuth.fromIntent(intent)
        setContent {
            val db = DatabaseProvider.get(this)
            val themeMode = runBlocking { com.qiradev.cloudnest.ui.loadThemeMode(db) }
            CloudNestTheme(themeMode) {
                CloudNestApp(oauthCallback = oauthCallback.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        oauthCallback.value = CloudNestOAuth.fromIntent(intent)
    }
}
