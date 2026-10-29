package com.qiradev.cloudnest.engine.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.DatabaseProvider
import com.qiradev.cloudnest.data.entity.AuditEventType
import com.qiradev.cloudnest.data.entity.ProviderAccountStatus
import com.qiradev.cloudnest.data.entity.QuotaStatus
import com.qiradev.cloudnest.data.testAccount
import com.qiradev.cloudnest.data.testFile
import com.qiradev.cloudnest.data.testFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * specs/08 §4 — manual backup/restore round trip at mock level: export → wipe
 * (new phone) → restore → the archive ledger is back, accounts sit at
 * "needs login", and the restore is audited.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun freshDatabase() {
        // Hermetic start: the DatabaseProvider singleton and its files persist
        // across methods in one sandbox — every test restores from empty.
        DatabaseProvider.closeAndReset()
        val dir = context.noBackupFilesDir
        listOf(
            CloudNestDatabase.DATABASE_NAME,
            "${CloudNestDatabase.DATABASE_NAME}-wal",
            "${CloudNestDatabase.DATABASE_NAME}-shm",
        ).forEach { name -> File(dir, name).delete() }
    }

    private fun productionDb(): CloudNestDatabase = DatabaseProvider.get(context)

    private fun populate(db: CloudNestDatabase) = runBlocking {
        val account = testAccount(id = "acc-backup").copy(encryptedTokens = byteArrayOf(1, 2, 3))
        db.providerAccountDao().insert(account)
        db.folderDao().insert(testFolder(id = "folder-1", name = "docs"))
        db.fileDao().insert(testFile(id = "file-1", folderId = "folder-1", name = "a.mp4"))
    }

    @Test
    fun exportRoundTripRestoresTheArchiveOnANewPhone(): Unit = runBlocking {
        populate(productionDb())
        val bytes = ByteArrayOutputStream().use { out ->
            Backup.exportTo(context, productionDb(), out)
            out.toByteArray()
        }
        assertTrue(bytes.size > 100)

        // New phone: nothing on disk before the restore.
        DatabaseProvider.closeAndReset()
        val dir = context.noBackupFilesDir
        File(dir, CloudNestDatabase.DATABASE_NAME).delete()
        File(dir, "${CloudNestDatabase.DATABASE_NAME}-wal").delete()
        File(dir, "${CloudNestDatabase.DATABASE_NAME}-shm").delete()

        val staged = Backup.stageBackup(context, ByteArrayInputStream(bytes))
        Backup.replaceDatabase(context, staged)
        val restored = productionDb()
        Backup.markAccountsNeedLogin(restored)

        val file = restored.fileDao().getById("file-1")
        assertNotNull(file)
        assertEquals("a.mp4", file!!.name)
        val account = restored.providerAccountDao().getById("acc-backup")!!
        assertEquals(ProviderAccountStatus.DISCONNECTED, account.status)
        assertNull(account.encryptedTokens)
        assertEquals(QuotaStatus.UNKNOWN, account.quotaStatus)
        val audits = restored.auditEventDao().latest(10)
        assertTrue(audits.any { it.type == AuditEventType.BACKUP_RESTORED })
    }

    @Test
    fun exportProducesACompleteSqliteFileEvenWithWalPending(): Unit = runBlocking {
        populate(productionDb())
        val bytes = ByteArrayOutputStream().use { out ->
            Backup.exportTo(context, productionDb(), out)
            out.toByteArray()
        }
        // Header check + the newest write (this very session) must be inside the copy.
        val header = "SQLite format 3\u0000".toByteArray()
        assertTrue(bytes.size > header.size)
        assertTrue(bytes.copyOfRange(0, header.size).contentEquals(header))
        val file = File.createTempFile("validate-", ".db").apply { writeBytes(bytes) }
        val staged = Backup.validateFile(file)
        assertEquals(CloudNestDatabase.SCHEMA_VERSION, staged.schemaVersion)
        assertEquals(bytes.size.toLong(), staged.fileSizeBytes)
        file.delete()
    }

    @Test
    fun rejectsGarbageFiles(): Unit = runBlocking {
        val junk = File.createTempFile("junk-", ".bin").apply { writeBytes("hello world".toByteArray()) }
        try {
            Backup.validateFile(junk)
            throw AssertionError("expected InvalidBackupException")
        } catch (e: Backup.InvalidBackupException) {
            assertEquals("not_sqlite", e.reason)
        }
    }

    @Test
    fun rejectsABackupFromANewerSchemaVersion(): Unit = runBlocking {
        populate(productionDb())
        // Pretend the backup came from a future app: bump the recorded version.
        productionDb().openHelper.writableDatabase.execSQL(
            "UPDATE meta SET `value` = '${CloudNestDatabase.SCHEMA_VERSION + 1}' WHERE `key` = 'schema_version'",
        )
        val bytes = ByteArrayOutputStream().use { out ->
            Backup.exportTo(context, productionDb(), out)
            out.toByteArray()
        }
        val file = File.createTempFile("newer-", ".db").apply { writeBytes(bytes) }
        try {
            Backup.validateFile(file)
            throw AssertionError("expected InvalidBackupException for newer schema")
        } catch (e: Backup.InvalidBackupException) {
            assertEquals("backup_newer", e.reason)
        } finally {
            file.delete()
        }
    }
}
