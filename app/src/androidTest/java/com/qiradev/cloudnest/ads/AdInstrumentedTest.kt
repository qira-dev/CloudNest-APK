package com.qiradev.cloudnest.ads

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qiradev.cloudnest.MainActivity
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * specs/07 §6 — the acceptance at test-id level: on a device with Google
 * services (Play-image emulator), the SDK initializes and a real test-ad
 * banner loads. Run on the CloudNestTestPlay AVD (google_apis_playstore);
 * on a plain AOSP image there is no GMS and this test cannot load ads.
 */
@RunWith(AndroidJUnit4::class)
class AdInstrumentedTest {

    @Test
    fun mobileAdsInitializesAndTestBannerLoads() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                MobileAds.initialize(activity) {}
            }

            val loaded = CountDownLatch(1)
            val failure = arrayOf<LoadAdError?>(null)
            scenario.onActivity { activity ->
                val adView = AdView(activity).apply {
                    setAdSize(AdSize.BANNER)
                    adUnitId = AdConfig.testAdUnitId(AdConfig.Format.BANNER)
                    adListener = object : AdListener() {
                        override fun onAdLoaded() {
                            loaded.countDown()
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            failure[0] = error
                            loaded.countDown()
                        }
                    }
                }
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                content.addView(
                    adView,
                    FrameLayout.LayoutParams(1, 1),
                )
                adView.loadAd(AdRequest.Builder().build())
            }

            assertTrue("banner did not settle within 60s", loaded.await(60, TimeUnit.SECONDS))
            assertTrue(
                "banner failed to load: ${failure[0]?.message}",
                failure[0] == null,
            )
        }
    }
}
