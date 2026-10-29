package com.qiradev.cloudnest

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.qiradev.cloudnest.ads.AppOpenManager
import com.qiradev.cloudnest.ads.ConsentManager
import com.qiradev.cloudnest.ads.InterstitialManager
import com.qiradev.cloudnest.background.UploadForegroundService
import com.qiradev.cloudnest.data.DatabaseProvider
import com.qiradev.cloudnest.ui.AppLocales
import com.qiradev.cloudnest.ui.CloudNestApp
import com.qiradev.cloudnest.ui.CloudNestOAuth
import com.qiradev.cloudnest.ui.CloudNestTheme
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    /** Latest OAuth redirect (specs/04 §2) — set at launch and on every re-entry. */
    private val oauthCallback = mutableStateOf<CloudNestOAuth?>(null)

    private val interstitials = InterstitialManager()
    private val appOpen = AppOpenManager()

    override fun attachBaseContext(base: Context) {
        // specs/09 §13 — the stored language wraps the whole activity context.
        val tag = runBlocking { AppLocales.savedTag(DatabaseProvider.get(base)) }
        super.attachBaseContext(AppLocales.wrapContext(base, tag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        oauthCallback.value = CloudNestOAuth.fromIntent(intent)
        // specs/07 §4: SDK init + UMP consent on the first launch — both guarded;
        // a device without Google services (plain emulator) simply skips ads.
        runCatching { MobileAds.initialize(this) {} }
        val db = DatabaseProvider.get(this)
        ConsentManager.requestConsent(this, db, lifecycleScope)

        setContent {
            val themeMode = runBlocking { com.qiradev.cloudnest.ui.loadThemeMode(db) }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        // specs/07 §3: at most one per 3 minutes, never mid-transfer.
                        interstitials.maybeShow(this@MainActivity, UploadForegroundService.draining)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            CloudNestTheme(themeMode) {
                CloudNestApp(
                    oauthCallback = oauthCallback.value,
                    onAppContentVisible = {
                        // specs/07 §3: app-open once per session, never over the gate.
                        appOpen.maybeShow(this@MainActivity, gateReady = true)
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        oauthCallback.value = CloudNestOAuth.fromIntent(intent)
    }
}
