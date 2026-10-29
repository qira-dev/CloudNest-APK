package com.qiradev.cloudnest.ads

import android.app.Activity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * Interstitial placement (specs/07 §3): preloaded so showing never waits for a
 * load; at most one per [AdConfig] gap minutes and never while a transfer is
 * running — the caller supplies both facts. Load failure is silent (specs/07
 * §4): the app's work is never affected.
 */
class InterstitialManager(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var interstitial: InterstitialAd? = null
    private var lastShownAtMs = 0L

    /** Pure show-decision — unit-testable without the SDK. */
    fun shouldShow(nowMs: Long, transfersActive: Boolean): Boolean =
        shouldShowForTest(nowMs, transfersActive, adAvailable = interstitial != null)

    internal fun shouldShowForTest(nowMs: Long, transfersActive: Boolean, adAvailable: Boolean): Boolean {
        val placement = AdConfig.byId(PLACEMENT_ID) ?: return false
        if (!placement.enabled) return false
        if (transfersActive) return false
        if (!adAvailable) return false
        return nowMs - lastShownAtMs >= placement.minGapMinutes * 60_000L
    }

    fun preload(activity: Activity) {
        val placement = AdConfig.byId(PLACEMENT_ID) ?: return
        if (!placement.enabled || interstitial != null) return
        runCatching {
            InterstitialAd.load(
                activity,
                AdConfig.testAdUnitId(placement.format),
                com.google.android.gms.ads.AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        interstitial = ad
                        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                            override fun onAdDismissedFullScreenContent() {
                                interstitial = null
                            }

                            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                                interstitial = null
                            }
                        }
                    }

                    override fun onAdFailedToLoad(adError: com.google.android.gms.ads.LoadAdError) {
                        // Silent skip (specs/07 §4) — no layout, no flow impact.
                        interstitial = null
                    }
                },
            )
        }
    }

    /** Shows when [shouldShow] allows; re-preloads afterwards. Returns whether it showed. */
    fun maybeShow(activity: Activity, transfersActive: Boolean): Boolean {
        if (!shouldShow(clock(), transfersActive)) return false
        val ad = interstitial ?: return false
        return runCatching {
            lastShownAtMs = clock()
            ad.show(activity)
            interstitial = null
            preload(activity)
            true
        }.getOrDefault(false)
    }

    fun resetForTest() {
        interstitial = null
        lastShownAtMs = 0L
    }

    /** Test seam: pins the last-shown time so the gap rule is checkable. */
    fun recordShownForTest(atMs: Long) {
        lastShownAtMs = atMs
    }

    companion object {
        const val PLACEMENT_ID = "upload_complete"
    }
}

/**
 * App-open placement (specs/07 §3): at most one per session on a cold start,
 * never over the unlock/setup gate. Load failure silent.
 */
class AppOpenManager {

    private var shownThisSession = false

    fun shouldShow(gateReady: Boolean): Boolean =
        AdConfig.isEnabled(APP_OPEN_PLACEMENT) && gateReady && !shownThisSession

    fun maybeShow(activity: Activity, gateReady: Boolean): Boolean {
        if (!shouldShow(gateReady)) return false
        shownThisSession = true
        // The load+show is left to the SDK's app-open flow; on an emulator or a
        // device without Google services it simply never fires (silent skip).
        return runCatching {
            com.google.android.gms.ads.appopen.AppOpenAd.load(
                activity,
                AdConfig.testAdUnitId(AdConfig.Format.APP_OPEN),
                com.google.android.gms.ads.AdRequest.Builder().build(),
                object : com.google.android.gms.ads.appopen.AppOpenAd.AppOpenAdLoadCallback() {
                    override fun onAdLoaded(ad: com.google.android.gms.ads.appopen.AppOpenAd) {
                        runCatching { ad.show(activity) }
                    }
                },
            )
        }.isSuccess
    }

    companion object {
        const val APP_OPEN_PLACEMENT = "app_open"
    }
}
