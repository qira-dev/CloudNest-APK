package com.qiradev.cloudnest.data

/**
 * Settings keys (specs/01 §1.2). Values are stored as strings; JSON when structured.
 * Only the defaults the spec fixes are listed here — mirror/language/theme defaults
 * are engine/UI concerns and must not be invented at the data layer.
 */
object SettingsKeys {
    const val UNLOCK_HASH = "unlock_hash"
    const val WIFI_ONLY_UPLOAD = "wifi_only_upload"
    const val CHUNK_SIZE_MIB = "chunk_size_mib"
    const val RESERVE_PER_ACCOUNT_BYTES = "reserve_per_account_bytes"
    const val SAFETY_MARGIN_BYTES = "safety_margin_bytes"
    const val MIRROR_ENABLED = "mirror_enabled"
    const val LANGUAGE = "language"
    const val THEME_MODE = "theme_mode"
    const val TRASH_RETENTION_DAYS = "trash_retention_days"
    const val ADMOB_CONSENT_GIVEN_AT = "admob_consent_given_at"

    object Defaults {
        const val WIFI_ONLY_UPLOAD = "true"
        const val CHUNK_SIZE_MIB = "64"
        const val RESERVE_PER_ACCOUNT_BYTES = "0"
        const val SAFETY_MARGIN_BYTES = "0"
        const val TRASH_RETENTION_DAYS = "7"
    }
}
