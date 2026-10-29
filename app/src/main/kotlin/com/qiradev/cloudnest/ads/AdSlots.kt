package com.qiradev.cloudnest.ads

import android.app.Activity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

/**
 * The shared ad slot (specs/07 §2): `AdSlot("home_bottom")` drops the right ad
 * for that placement wherever the screen puts it. The ads layer reads nothing
 * from the engine database (specs/07 §6) — engine-independent by design.
 *
 * Load failure is a silent skip (specs/07 §4): the slot collapses to zero
 * height until an ad actually arrives, so a failed load leaves the layout
 * intact and the app untouched.
 */
@Composable
fun AdSlot(placementId: String, modifier: Modifier = Modifier) {
    val placement = AdConfig.byId(placementId) ?: return
    if (!placement.enabled) return
    when (placement.format) {
        AdConfig.Format.BANNER -> BannerSlot(placementId, modifier)
        // Native renders as a list item (NativeAdSlotItem); interstitial /
        // app-open / rewarded are not in-layout.
        else -> {}
    }
}

@Composable
fun BannerSlot(placementId: String, modifier: Modifier = Modifier) {
    var loaded by remember { mutableStateOf(false) }
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .then(if (loaded) Modifier.height(50.dp) else Modifier.height(0.dp)),
        factory = { context ->
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = AdConfig.testAdUnitId(AdConfig.Format.BANNER)
                adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        loaded = true
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        // Silent skip — the slot stays collapsed (specs/07 §4).
                        loaded = false
                    }
                }
                loadAd(AdRequest.Builder().build())
            }
        },
    )
}

/**
 * Native slot item for the archive list (specs/07 §3: one every N items). A
 * minimal programmatic native view (headline + body) — the owner's screenshots
 * refine the presentation later; a failed load renders nothing.
 */
@Composable
fun NativeAdSlotItem(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var loaded by remember { mutableStateOf(false) }
    var headline by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching {
            com.google.android.gms.ads.AdLoader.Builder(
                context,
                AdConfig.testAdUnitId(AdConfig.Format.NATIVE),
            )
                .forNativeAd { ad ->
                    headline = ad.headline ?: ""
                    body = ad.body ?: ""
                    loaded = true
                    ad.destroy()
                }
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        loaded = false
                    }
                })
                .build()
                .loadAd(AdRequest.Builder().build())
        }
    }

    if (loaded) {
        androidx.compose.material3.ListItem(
            headlineContent = { androidx.compose.material3.Text(headline) },
            supportingContent = { androidx.compose.material3.Text(body) },
            trailingContent = {
                androidx.compose.material3.Text(
                    androidx.compose.ui.res.stringResource(com.qiradev.cloudnest.R.string.ad_label),
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            },
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }
}
