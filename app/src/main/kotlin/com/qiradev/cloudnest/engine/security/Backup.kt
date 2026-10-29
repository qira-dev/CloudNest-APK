package com.qiradev.cloudnest.engine.security

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.DatabaseProvider
import com.qiradev.cloudnest.data.entity.AuditEventEntity
import com.qiradev.cloudnest.data.entity.AuditEventType
import com.qiradev.cloudnest.data.entity.ProviderAccountStatus
import com.qiradev.cloudnest.data.entity.QuotaStatus
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Manual backup/restore (specs/08 §4): the complete metadata database is
 * exported by hand through SAF — Android auto-backup stays off. Tokens are NOT
 * backed up (the Keystore master key cannot leave the phone), so a restored
 * archive comes back with every account "needs login" (§4.3).
 */
object Backup {

    const val BACKUP_MIME = "application/octet-stream"
    const val SQLITE_HEADER = "SQLite format 3\u0000"

    class InvalidBackupException(val reason: String) :
        Exception("invalid backup: $reason")

    data class BackupInfo(val schemaVersion: Int, val fileSizeBytes: Long)

    /** Export file name carries the date (specs/08 §4.2). */
    fun backupFileName(now: Long = System.currentTimeMillis()): String {
        val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return "cloudnest-backup-${format.format(Date(now))}.db"
    }

    fun databaseFile(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, CloudNestDatabase.DATABASE_NAME)

    /**
     * Streams the complete database file to the SAF output. A WAL checkpoint
     * (TRUNCATE) first folds every committed page into the main file so the
     * single .db copy is complete (specs/01 §0 — WAL).
     */
    fun exportTo(context: Context, db: CloudNestDatabase, output: OutputStream): Long {
        val database = db.openHelper.writableDatabase
        database.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val file = databaseFile(context)
        file.inputStream().use { input ->
            output.use { stream -> input.copyTo(stream) }
        }
        return file.length()
    }

    /**
     * Copies the SAF input into a private temp file and validates it: SQLite
     * header, a `meta.schema_version` row (this is a CloudNest archive ledger),
     * a passing integrity check, and a version the current app can open
     * (older → migrations apply on reopen; newer → the app must be updated).
     */
    fun stageBackup(context: Context, input: InputStream): File {
        val temp = File.createTempFile("cloudnest-restore-", ".db", context.cacheDir)
        try {
            temp.outputStream().use { stream -> input.copyTo(stream) }
            validateFile(temp)
            return temp
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    fun validateFile(file: File): BackupInfo {
        val header = ByteArray(SQLITE_HEADER.length)
        file.inputStream().use { stream ->
            if (stream.read(header) != header.size || !header.contentEquals(SQLITE_HEADER.toByteArray())) {
                throw InvalidBackupException("not_sqlite")
            }
        }
        val schemaVersion = try {
            val raw = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                // Typed empty selectionArgs keeps the rawQuery overload unambiguous.
                val noArgs: Array<String?> = arrayOfNulls(0)
                raw.rawQuery("PRAGMA integrity_check", noArgs).use { cursor ->
                    val result = if (cursor.moveToFirst()) cursor.getString(0) else ""
                    if (result != "ok") throw InvalidBackupException("integrity_$result")
                }
                raw.rawQuery(
                    "SELECT `value` FROM meta WHERE `key` = 'schema_version'",
                    noArgs,
                ).use { cursor ->
                    if (!cursor.moveToFirst()) throw InvalidBackupException("not_cloudnest")
                    cursor.getString(0).toIntOrNull() ?: throw InvalidBackupException("bad_version")
                }
            } finally {
                raw.close()
            }
        } catch (e: InvalidBackupException) {
            throw e
        } catch (e: Exception) {
            throw InvalidBackupException("not_cloudnest")
        }
        if (schemaVersion > CloudNestDatabase.SCHEMA_VERSION) {
            throw InvalidBackupException("backup_newer")
        }
        return BackupInfo(schemaVersion, file.length())
    }

    /**
     * Swaps the staged backup into place: the Room singleton closes first, the
     * old files (db/WAL/SHM) are removed and the validated copy takes the name.
     * The next [DatabaseProvider.get] opens it — migrations run when the backup
     * is older than the current schema.
     */
    fun replaceDatabase(context: Context, staged: File) {
        DatabaseProvider.closeAndReset()
        val dir = context.applicationContext.noBackupFilesDir
        listOf(
            CloudNestDatabase.DATABASE_NAME,
            "${CloudNestDatabase.DATABASE_NAME}-wal",
            "${CloudNestDatabase.DATABASE_NAME}-shm",
        ).forEach { name -> File(dir, name).delete() }
        staged.copyTo(File(dir, CloudNestDatabase.DATABASE_NAME), overwrite = true)
        staged.delete()
    }

    /**
     * Post-restore accounting (specs/08 §4.2–4.3): every restored account flips
     * to "disconnected (needs login)" — its tokens were encrypted with the old
     * phone's Keystore key and are unreadable here — and the restore is audited.
     */
    suspend fun markAccountsNeedLogin(db: CloudNestDatabase, now: Long = System.currentTimeMillis()) {
        val dao = db.providerAccountDao()
        val accounts = dao.getAll()
        accounts.forEach { account ->
            dao.update(
                account.copy(
                    encryptedTokens = null,
                    tokenExpiresAt = 0,
                    status = ProviderAccountStatus.DISCONNECTED,
                    quotaStatus = QuotaStatus.UNKNOWN,
                ),
            )
        }
        db.auditEventDao().insert(
            AuditEventEntity(
                id = UUID.randomUUID().toString(),
                type = AuditEventType.BACKUP_RESTORED,
                detailJson = "{\"accounts\":${accounts.size}}",
                createdAt = now,
            ),
        )
    }
}
