package com.qiradev.cloudnest.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.qiradev.cloudnest.data.dao.AuditEventDao
import com.qiradev.cloudnest.data.dao.ChunkPlacementDao
import com.qiradev.cloudnest.data.dao.DeletionAttemptDao
import com.qiradev.cloudnest.data.dao.FileChunkDao
import com.qiradev.cloudnest.data.dao.FileDao
import com.qiradev.cloudnest.data.dao.FolderDao
import com.qiradev.cloudnest.data.dao.MetaDao
import com.qiradev.cloudnest.data.dao.NotificationDao
import com.qiradev.cloudnest.data.dao.ProviderAccountDao
import com.qiradev.cloudnest.data.dao.ReservationDao
import com.qiradev.cloudnest.data.dao.SettingsDao
import com.qiradev.cloudnest.data.dao.TransferJobDao
import com.qiradev.cloudnest.data.dao.UploadAttemptDao
import com.qiradev.cloudnest.data.entity.AuditEventEntity
import com.qiradev.cloudnest.data.entity.ChunkPlacementEntity
import com.qiradev.cloudnest.data.entity.DeletionAttemptEntity
import com.qiradev.cloudnest.data.entity.FileChunkEntity
import com.qiradev.cloudnest.data.entity.FileEntity
import com.qiradev.cloudnest.data.entity.FolderEntity
import com.qiradev.cloudnest.data.entity.MetaEntity
import com.qiradev.cloudnest.data.entity.NotificationEntity
import com.qiradev.cloudnest.data.entity.ProviderAccountEntity
import com.qiradev.cloudnest.data.entity.ReservationEntity
import com.qiradev.cloudnest.data.entity.SettingsEntity
import com.qiradev.cloudnest.data.entity.TransferJobEntity
import com.qiradev.cloudnest.data.entity.UploadAttemptEntity

/**
 * The app database (specs/01). WAL + foreign keys are enforced in [CALLBACK];
 * the schema lives in `app/schemas` (exported) so migrations stay testable.
 */
@Database(
    entities = [
        MetaEntity::class,
        SettingsEntity::class,
        ProviderAccountEntity::class,
        FolderEntity::class,
        FileEntity::class,
        FileChunkEntity::class,
        ChunkPlacementEntity::class,
        ReservationEntity::class,
        TransferJobEntity::class,
        UploadAttemptEntity::class,
        DeletionAttemptEntity::class,
        NotificationEntity::class,
        AuditEventEntity::class,
    ],
    version = CloudNestDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
abstract class CloudNestDatabase : RoomDatabase() {
    abstract fun metaDao(): MetaDao
    abstract fun settingsDao(): SettingsDao
    abstract fun providerAccountDao(): ProviderAccountDao
    abstract fun folderDao(): FolderDao
    abstract fun fileDao(): FileDao
    abstract fun fileChunkDao(): FileChunkDao
    abstract fun chunkPlacementDao(): ChunkPlacementDao
    abstract fun reservationDao(): ReservationDao
    abstract fun transferJobDao(): TransferJobDao
    abstract fun uploadAttemptDao(): UploadAttemptDao
    abstract fun deletionAttemptDao(): DeletionAttemptDao
    abstract fun notificationDao(): NotificationDao
    abstract fun auditEventDao(): AuditEventDao

    companion object {
        const val DATABASE_NAME = "cloudnest.db"
        const val SCHEMA_VERSION = 1

        /**
         * Versioned migrations (specs/01 §0): an applied migration is never edited —
         * every schema change after v1 appends a new [Migration] here and bumps
         * [SCHEMA_VERSION].
         */
        val MIGRATIONS: Array<Migration> = arrayOf()

        /** Shared by the production builder and the in-memory test builders. */
        val CALLBACK: Callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("INSERT INTO meta (`key`, `value`) VALUES ('schema_version', '$SCHEMA_VERSION')")
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
                // Idempotent; covers the relocated-context path where Room's open-flag
                // WAL request cannot reach the file system.
                db.query("PRAGMA journal_mode=WAL").use { it.moveToFirst() }
            }
        }
    }
}
