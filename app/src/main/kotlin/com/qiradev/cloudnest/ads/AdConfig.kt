package com.qiradev.cloudnest.ads

/**
 * Ad placement registry (specs/07 §2): an ad enters a screen by placement id,
 * never sprinkled through code. Changing a spot = editing this one table +
 * a new build (specs/07 §2 — remote config is a later, open question).
 *
 * Ad-forbidden screens (specs/07 §3) are absent by construction: unlock/setup,
 * file detail, settings/diagnostics and anything mid-transfer never appear here.
 *
 * The unit ids below are Google's PUBLISHED test ids — public and safe to
 * commit; the owner's real AdMob units (specs/07 §1.4) replace them for release.
 */
object AdConfig {

    enum class Format { BANNER, NATIVE, INTERSTITIAL, REWARDED, APP_OPEN }

    data class Placement(
        val id: String,
        val format: Format,
        val enabled: Boolean,
        /** NATIVE only: one ad every N list items (specs/07 §3). */
        val everyNItems: Int = 0,
        /** INTERSTITIAL only: minimum minutes between two shows (specs/07 §3). */
        val minGapMinutes: Int = 0,
    )

    val PLACEMENTS: List<Placement> = listOf(
        Placement(id = "home_bottom", format = Format.BANNER, enabled = true),
        Placement(id = "list_native", format = Format.NATIVE, enabled = true, everyNItems = 12),
        Placement(
            id = "upload_complete",
            format = Format.INTERSTITIAL,
            enabled = true,
            minGapMinutes = 3,
        ),
        Placement(id = "app_open", format = Format.APP_OPEN, enabled = true),
        Placement(id = "mirror_unlock", format = Format.REWARDED, enabled = false),
    )

    fun byId(id: String): Placement? = PLACEMENTS.firstOrNull { it.id == id }

    fun isEnabled(id: String): Boolean = byId(id)?.enabled == true

    /** Google-published TEST application id (public, specs/07 §4). */
    const val TEST_APPLICATION_ID = "ca-app-pub-3940256099942544~3347511713"

    /** Google-published test ad-unit ids per format (public, specs/07 §4). */
    fun testAdUnitId(format: Format): String = when (format) {
        Format.BANNER -> "ca-app-pub-3940256099942544/6300978111"
        Format.NATIVE -> "ca-app-pub-3940256099942544/2247696110"
        Format.INTERSTITIAL -> "ca-app-pub-3940256099942544/1033173712"
        Format.REWARDED -> "ca-app-pub-3940256099942544/5224354917"
        Format.APP_OPEN -> "ca-app-pub-3940256099942544/3419835294"
    }
}
