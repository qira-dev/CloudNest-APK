package com.qiradev.cloudnest.data

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/**
 * Database entry point. The database file lives in `noBackupFilesDir` (specs/01 §3):
 * Android auto-backup must never carry the archive metadata off the device —
 * backup/restore is manual through SAF (specs/08).
 */
object DatabaseProvider {

    @Volatile
    private var instance: CloudNestDatabase? = null

    fun get(context: Context): CloudNestDatabase =
        instance ?: synchronized(this) {
            instance ?: build(context).also { instance = it }
        }

    /** Builds an independent database instance (tests); [get] is the production entry point. */
    fun build(context: Context): CloudNestDatabase {
        val appContext = context.applicationContext
        return Room.databaseBuilder(
            RelocatingDatabaseContext(appContext, appContext.noBackupFilesDir),
            CloudNestDatabase::class.java,
            CloudNestDatabase.DATABASE_NAME,
        )
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(*CloudNestDatabase.MIGRATIONS)
            .addCallback(CloudNestDatabase.CALLBACK)
            // MainActivity.attachBaseContext reads the saved language before any
            // coroutine scope exists — a single indexed settings row on the
            // main thread; every other access stays off the main thread.
            .allowMainThreadQueries()
            .build()
    }

    /**
     * Backup/restore support (specs/08 §4.2): closes the singleton and clears it so
     * the next [get] opens the (replaced) database file fresh — versioned migrations
     * apply on that open. Callers must not keep using a previous instance.
     */
    fun closeAndReset() {
        synchronized(this) {
            instance?.let { db -> runCatching { db.close() } }
            instance = null
        }
    }

    /**
     * Room resolves database names through the context, so this wrapper redirects
     * open/delete/getDatabasePath into [directory].
     */
    private class RelocatingDatabaseContext(base: Context, private val directory: File) : ContextWrapper(base) {

        private fun file(name: String): File = File(directory, name)

        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            openOrCreateDatabase(name, mode, factory, null)

        // Path-based open (instead of context-based) keeps minSdk 26 support; WAL and
        // foreign keys are enforced in CloudNestDatabase.CALLBACK.onConfigure.
        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?,
        ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file(name).path, factory, errorHandler)

        override fun deleteDatabase(name: String?): Boolean = name != null && file(name).delete()

        override fun getDatabasePath(name: String?): File = file(requireNotNull(name))
    }
}
