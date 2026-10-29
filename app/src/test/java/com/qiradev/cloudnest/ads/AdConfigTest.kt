package com.qiradev.cloudnest.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * specs/07 §2+§3 — the placement registry and the interstitial decision logic,
 * pure JVM: an ad enters by placement id, forbidden screens are absent by
 * construction, and the 3-minute gap + no-mid-transfer rule hold.
 */
class AdConfigTest {

    @Test
    fun placementsMatchTheSpecMap() {
        val home = AdConfig.byId("home_bottom")!!
        assertEquals(AdConfig.Format.BANNER, home.format)
        assertTrue(home.enabled)

        val native = AdConfig.byId("list_native")!!
        assertEquals(AdConfig.Format.NATIVE, native.format)
        assertEquals(12, native.everyNItems)
        assertTrue(native.enabled)

        val interstitial = AdConfig.byId("upload_complete")!!
        assertEquals(AdConfig.Format.INTERSTITIAL, interstitial.format)
        assertEquals(3, interstitial.minGapMinutes)
        assertTrue(interstitial.enabled)

        val appOpen = AdConfig.byId("app_open")!!
        assertEquals(AdConfig.Format.APP_OPEN, appOpen.format)
        assertTrue(appOpen.enabled)

        // specs/07 §3: rewarded stays off unless the owner turns it on later.
        val rewarded = AdConfig.byId("mirror_unlock")!!
        assertEquals(AdConfig.Format.REWARDED, rewarded.format)
        assertFalse(rewarded.enabled)
    }

    @Test
    fun adForbiddenScreensAreAbsentFromPlacements() {
        val ids = AdConfig.PLACEMENTS.map { it.id }
        // specs/07 §3: unlock/setup, file detail, settings/diagnostics never
        // carry ads — and mid-transfer surfaces are interstitial-gated, not slots.
        listOf("unlock", "setup", "file_detail", "settings", "diagnostics", "trash").forEach {
            assertFalse("placement $it must not exist", ids.contains(it))
        }
        assertNull(AdConfig.byId("file_detail"))
    }

    @Test
    fun unknownPlacementIsNotEnabled() {
        assertNull(AdConfig.byId("does_not_exist"))
        assertFalse(AdConfig.isEnabled("does_not_exist"))
    }

    @Test
    fun unitIdsAreGooglesPublishedTestIds() {
        // Public test ids (specs/07 §4) — the real owner units replace them only
        // at release; these never hit a live account.
        assertEquals("ca-app-pub-3940256099942544/6300978111", AdConfig.testAdUnitId(AdConfig.Format.BANNER))
        assertEquals("ca-app-pub-3940256099942544/2247696110", AdConfig.testAdUnitId(AdConfig.Format.NATIVE))
        assertEquals("ca-app-pub-3940256099942544/1033173712", AdConfig.testAdUnitId(AdConfig.Format.INTERSTITIAL))
        assertEquals("ca-app-pub-3940256099942544/5224354917", AdConfig.testAdUnitId(AdConfig.Format.REWARDED))
        assertEquals("ca-app-pub-3940256099942544/3419835294", AdConfig.testAdUnitId(AdConfig.Format.APP_OPEN))
        AdConfig.PLACEMENTS.forEach { placement ->
            assertTrue(AdConfig.testAdUnitId(placement.format).startsWith("ca-app-pub-3940256099942544/"))
        }
    }
}

class InterstitialDecisionTest {

    private val gapMs = 3L * 60 * 1000

    @Test
    fun showsAfterTheGapWhenIdleAndLoaded() {
        val manager = InterstitialManager()
        val now = 1_000_000L
        assertTrue(manager.shouldShowForTest(now, transfersActive = false, adAvailable = true))
    }

    @Test
    fun neverDuringTransfers() {
        val manager = InterstitialManager()
        assertFalse(manager.shouldShowForTest(1_000_000L, transfersActive = true, adAvailable = true))
    }

    @Test
    fun respectsTheThreeMinuteGap() {
        val manager = InterstitialManager()
        val shownAt = 1_000_000L
        manager.recordShownForTest(shownAt)
        // 2m59s later — still inside the gap.
        assertFalse(manager.shouldShowForTest(shownAt + gapMs - 1000, transfersActive = false, adAvailable = true))
        // 3m later — allowed again.
        assertTrue(manager.shouldShowForTest(shownAt + gapMs, transfersActive = false, adAvailable = true))
    }

    @Test
    fun withoutAPreloadedAdItNeverShows() {
        val manager = InterstitialManager()
        assertFalse(manager.shouldShowForTest(1_000_000L, transfersActive = false, adAvailable = false))
    }
}
