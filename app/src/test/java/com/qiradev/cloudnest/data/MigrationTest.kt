package com.qiradev.cloudnest.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * specs/10 SchemaTest — migration 1→N. At schema v1 the suite validates the
 * exported schema: creation succeeds, all spec tables exist, and the current
 * version opens without validation errors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {

    private val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CloudNestDatabase::class.java,
    )

    @Test
    fun schemaV1ContainsAllSpecTables() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            TABLES.forEach { table ->
                db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { cursor ->
                    assertTrue("table $table missing", cursor.moveToFirst())
                }
            }
        }
    }

    @Test
    fun openAtCurrentVersionPassesValidation() {
        helper.createDatabase(DB_NAME, 1).close()
        helper.runMigrationsAndValidate(DB_NAME, CloudNestDatabase.SCHEMA_VERSION, true, *CloudNestDatabase.MIGRATIONS).close()
    }

    private companion object {
        const val DB_NAME = "cloudnest-migration-test"

        val TABLES = listOf(
            "meta",
            "settings",
            "provider_accounts",
            "folders",
            "files",
            "file_chunks",
            "chunk_placements",
            "reservations",
            "transfer_jobs",
            "upload_attempts",
            "deletion_attempts",
            "notifications",
            "audit_events",
        )
    }
}
