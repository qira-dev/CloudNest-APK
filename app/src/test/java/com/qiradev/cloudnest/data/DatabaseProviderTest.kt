package com.qiradev.cloudnest.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** specs/01 §3 — production builder: noBackupFilesDir location, WAL, foreign keys, meta seed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseProviderTest {

    @Test
    fun buildsDatabaseInNoBackupFilesDirWithWalAndForeignKeys() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = DatabaseProvider.build(context)
        try {
            assertEquals("1", db.metaDao().getValue("schema_version"))

            assertTrue(File(context.noBackupFilesDir, CloudNestDatabase.DATABASE_NAME).exists())

            val sql = db.openHelper.writableDatabase
            sql.query("PRAGMA foreign_keys").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            sql.query("PRAGMA journal_mode").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("wal", cursor.getString(0).lowercase())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun getReturnsTheSameInstance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = DatabaseProvider.get(context)
        val second = DatabaseProvider.get(context)
        assertTrue(first === second)
        first.close()
    }
}
