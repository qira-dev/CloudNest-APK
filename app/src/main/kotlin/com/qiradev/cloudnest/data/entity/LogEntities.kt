package com.qiradev.cloudnest.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

object NotificationType {
    const val UPLOAD_DONE = "upload_done"
    const val UPLOAD_FAILED = "upload_failed"
    const val QUOTA_STALE = "quota_stale"
    const val TRASH_PURGED = "trash_purged"
    const val MIRROR_DONE = "mirror_done"
    const val MIRROR_FAILED = "mirror_failed"
    const val TOKEN_EXPIRED = "token_expired"
}

object AuditEventType {
    const val ACCOUNT_CONNECTED = "account_connected"
    const val ACCOUNT_DISCONNECTED = "account_disconnected"
    const val FILE_DELETED = "file_deleted"
    const val FILE_RESTORED = "file_restored"
    const val FILE_PURGED = "file_purged"
    const val BACKUP_RESTORED = "backup_restored"
    const val SETTINGS_CHANGED = "settings_changed"
}

/** In-app notification feed (specs/01 §1.12); last 100 kept. Important ones also reach the OS. */
@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "payload") val payload: String,
    @ColumnInfo(name = "read") val read: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Ledger of important actions (specs/01 §1.13); last 500 kept. */
@Entity(tableName = "audit_events")
data class AuditEventEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "detail_json") val detailJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
