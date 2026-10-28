package com.qiradev.cloudnest.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Provider identifiers (specs/01 §1.3; provider docs are the truth source — specs/04). */
object Providers {
    const val GOOGLE_DRIVE = "google_drive"
    const val DROPBOX = "dropbox"
    const val ONEDRIVE = "onedrive"
    const val PCLOUD = "pcloud"
    const val MEGA = "mega"
}

object ProviderAccountStatus {
    const val CONNECTED = "connected"
    const val DISCONNECTED = "disconnected"
    const val ERROR = "error"
}

/**
 * Quota freshness (specs/01 §1.3): `current` = last successful observation <= 72h old,
 * `stale` = older, `unknown` = never observed. `quota_observed_at` = 0 means never observed.
 */
object QuotaStatus {
    const val CURRENT = "current"
    const val STALE = "stale"
    const val UNKNOWN = "unknown"
}

@Entity(tableName = "provider_accounts")
data class ProviderAccountEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "provider") val provider: String,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "status") val status: String,
    /** Keystore-encrypted access/refresh token JSON (specs/08); bytes never leave the device. */
    @ColumnInfo(name = "encrypted_tokens") val encryptedTokens: ByteArray?,
    @ColumnInfo(name = "token_expires_at") val tokenExpiresAt: Long,
    @ColumnInfo(name = "quota_total_bytes") val quotaTotalBytes: Long?,
    @ColumnInfo(name = "quota_used_bytes") val quotaUsedBytes: Long?,
    /** 0/1 — 1 only for provider-documented unlimited accounts. */
    @ColumnInfo(name = "quota_unlimited") val quotaUnlimited: Int,
    @ColumnInfo(name = "quota_status") val quotaStatus: String,
    @ColumnInfo(name = "quota_observed_at") val quotaObservedAt: Long,
    /** Shared key for accounts of the same niche quota pool (specs/02 §2). */
    @ColumnInfo(name = "quota_scope_key") val quotaScopeKey: String?,
    /** Where the app keeps its chunks (e.g. own Drive folder id). */
    @ColumnInfo(name = "root_path") val rootPath: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long,
)
