package com.qiradev.cloudnest.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.qiradev.cloudnest.data.entity.AuditEventEntity
import com.qiradev.cloudnest.data.entity.NotificationEntity

@Dao
interface NotificationDao {
    @Insert
    suspend fun insert(notification: NotificationEntity)

    @Update
    suspend fun update(notification: NotificationEntity)

    @Query("SELECT * FROM notifications ORDER BY created_at DESC, id DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<NotificationEntity>

    @Query("SELECT * FROM notifications WHERE read = 0 ORDER BY created_at DESC")
    suspend fun listUnread(): List<NotificationEntity>

    @Query("UPDATE notifications SET read = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    /** Keeps the newest :keep rows, deletes the rest (specs/01 §1.12 — last 100). */
    @Query("DELETE FROM notifications WHERE id IN (SELECT id FROM notifications ORDER BY created_at DESC, id DESC LIMIT -1 OFFSET :keep)")
    suspend fun trimTo(keep: Int): Int

    @Query("DELETE FROM notifications WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface AuditEventDao {
    @Insert
    suspend fun insert(event: AuditEventEntity)

    @Query("SELECT * FROM audit_events ORDER BY created_at DESC, id DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<AuditEventEntity>

    /** Keeps the newest :keep rows, deletes the rest (specs/01 §1.13 — last 500). */
    @Query("DELETE FROM audit_events WHERE id IN (SELECT id FROM audit_events ORDER BY created_at DESC, id DESC LIMIT -1 OFFSET :keep)")
    suspend fun trimTo(keep: Int): Int
}
