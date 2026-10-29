package com.qiradev.cloudnest.background

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.entity.FileStatus
import com.qiradev.cloudnest.data.entity.JobStatus
import com.qiradev.cloudnest.data.entity.MetaEntity
import com.qiradev.cloudnest.data.entity.NotificationEntity
import com.qiradev.cloudnest.data.entity.NotificationType
import com.qiradev.cloudnest.data.entity.TransferJobEntity
import com.qiradev.cloudnest.engine.upload.UploadFileSource
import com.qiradev.cloudnest.engine.upload.UploadService
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** Upload source backed by a SAF document URI (specs/05 §5) — persisted permission. */
class SafUploadSource(
    private val context: Context,
    private val uri: Uri,
    private val storedSizeBytes: Long,
) : UploadFileSource {
    override fun sizeBytes(): Long = storedSizeBytes

    override fun openStream() =
        context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("unreadable document: permission revoked")
}

/**
 * Upload source backed by a private cache copy (specs/05 §4): share-target URIs
 * can lose their grant at any time, so the bytes are copied up front and the
 * upload reads from the copy; the copy is deleted when the job finishes.
 */
class CacheUploadSource(private val file: File) : UploadFileSource {
    override fun sizeBytes(): Long = file.length()

    override fun openStream() = file.inputStream()

    companion object {
        fun directory(context: Context): File = File(context.cacheDir, "shared-uploads")
    }
}

/**
 * Durable registry of a queued upload job's byte source (specs/02 §7): the
 * engine keeps sources in a transient map, so after a process death the
 * drivers rebuild them from here. Lives in the `meta` key-value table — no
 * schema change. Values: `{"kind":"saf","uri":…,"size":…}` or
 * `{"kind":"cache","path":…}`.
 */
object UploadSourceStore {
    private const val PREFIX = "upload_source:"

    suspend fun saveSaf(db: CloudNestDatabase, fileId: String, uri: Uri, sizeBytes: Long) {
        db.metaDao().upsert(
            MetaEntity(
                PREFIX + fileId,
                JSONObject().put("kind", "saf").put("uri", uri.toString()).put("size", sizeBytes).toString(),
            ),
        )
    }

    suspend fun saveCache(db: CloudNestDatabase, fileId: String, file: File) {
        db.metaDao().upsert(
            MetaEntity(
                PREFIX + fileId,
                JSONObject().put("kind", "cache").put("path", file.absolutePath).toString(),
            ),
        )
    }

    /** Rebuilds the source for a queued job; null when it is gone for good. */
    suspend fun restore(context: Context, db: CloudNestDatabase, fileId: String): UploadFileSource? {
        val raw = db.metaDao().getValue(PREFIX + fileId) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return when (json.optString("kind")) {
            "saf" -> runCatching {
                SafUploadSource(context, Uri.parse(json.getString("uri")), json.getLong("size"))
            }.getOrNull()
            "cache" -> File(json.getString("path")).takeIf { it.exists() }?.let { CacheUploadSource(it) }
            else -> null
        }
    }

    /**
     * Terminal-state cleanup (specs/05 §4): drops the registry entry and, for
     * share-target copies, deletes the cache file. Called on done/cancelled —
     * failed and paused jobs keep their source for retry/resume.
     */
    suspend fun clear(db: CloudNestDatabase, fileId: String) {
        val raw = db.metaDao().getValue(PREFIX + fileId)
        db.metaDao().delete(PREFIX + fileId)
        raw ?: return
        runCatching {
            val json = JSONObject(raw)
            if (json.optString("kind") == "cache") {
                File(json.getString("path")).delete()
            }
        }
    }
}

/** Shared driver helpers for the foreground service and [JobReviveWorker]. */
object UploadDrivers {

    /**
     * Re-registers a queued job's source from the durable store (specs/02 §7).
     * Returns false when the source is unrecoverable — the caller fails the job.
     */
    suspend fun ensureSource(
        context: Context,
        db: CloudNestDatabase,
        upload: UploadService,
        job: TransferJobEntity,
    ): Boolean {
        val fileId = job.fileId ?: return false
        if (upload.hasSource(fileId)) return true
        val source = UploadSourceStore.restore(context, db, fileId) ?: return false
        upload.registerSource(fileId, source)
        return true
    }

    /** Marks a queued job failed when its bytes can no longer be read at all. */
    suspend fun failWithoutSource(
        db: CloudNestDatabase,
        job: TransferJobEntity,
        now: Long = System.currentTimeMillis(),
    ) {
        // specs/05 §2.2 shape — file name for the notice text, job id for Retry.
        val fileJson = job.fileId?.let { db.fileDao().getById(it)?.name }
            ?.let { JSONObject.quote(it) } ?: "null"
        db.transferJobDao().update(
            job.copy(status = JobStatus.FAILED, errorCode = SOURCE_UNREADABLE, updatedAt = now),
        )
        job.fileId?.let { fileId ->
            db.fileDao().getById(fileId)?.let { file ->
                if (file.status != FileStatus.COMPLETE) {
                    db.fileDao().update(file.copy(status = FileStatus.FAILED, updatedAt = now))
                }
                UploadSourceStore.clear(db, fileId)
            }
        }
        db.notificationDao().insert(
            NotificationEntity(
                id = UUID.randomUUID().toString(),
                type = NotificationType.UPLOAD_FAILED,
                payload = """{"file":$fileJson,"code":"$SOURCE_UNREADABLE","job":"${job.id}"}""",
                read = 0,
                createdAt = now,
            ),
        )
        db.notificationDao().trimTo(100)
    }

    const val SOURCE_UNREADABLE = "source_unreadable"

    /**
     * Terminal-state cleanup shared by every driver (specs/05 §4): share-target
     * cache copies die with their job on done/cancelled; failed/paused jobs
     * keep their source for retry/resume.
     */
    suspend fun cleanupAfterTerminal(db: CloudNestDatabase, jobId: String, fileId: String) {
        val job = db.transferJobDao().getById(jobId) ?: return
        if (job.status == JobStatus.DONE || job.status == JobStatus.CANCELLED) {
            UploadSourceStore.clear(db, fileId)
        }
    }
}

/**
 * Enqueues uploads from incoming content URIs.
 * - Share target (specs/05 §4): copy to the app cache first — URI grants can be
 *   revoked — and upload from the copy.
 * - SAF picker (specs/05 §5): persistable read permission, upload in place.
 */
object ShareUploads {

    suspend fun enqueueFromShareUris(
        context: Context,
        db: CloudNestDatabase,
        upload: UploadService,
        uris: List<Uri>,
        folderId: String,
    ): List<String> = uris.mapNotNull { uri ->
        runCatching {
            val resolver = context.contentResolver
            val name = queryDisplayName(resolver, uri) ?: "shared-${System.currentTimeMillis()}"
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val dir = CacheUploadSource.directory(context).apply { mkdirs() }
            var target = File(dir, name)
            var n = 2
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            while (target.exists()) target = File(dir, "$base ($n)$ext").also { n++ }
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@mapNotNull null
            val jobId = upload.enqueue(CacheUploadSource(target), folderId, target.name, mime)
            // The durable store is keyed by FILE id (job rows die with retries).
            val fileId = db.transferJobDao().getById(jobId)?.fileId
            if (fileId == null) {
                db.transferJobDao().delete(jobId)
                return@mapNotNull null
            }
            UploadSourceStore.saveCache(db, fileId, target)
            jobId
        }.getOrNull()
    }

    suspend fun enqueueFromSafUris(
        context: Context,
        db: CloudNestDatabase,
        upload: UploadService,
        uris: List<Uri>,
        folderId: String,
        sizeOf: ((Uri) -> Long?)? = null,
    ): List<String> = uris.mapNotNull { uri ->
        runCatching {
            val resolver = context.contentResolver
            runCatching {
                resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = queryDisplayName(resolver, uri) ?: "upload-${System.currentTimeMillis()}"
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val size = sizeOf?.invoke(uri) ?: querySize(resolver, uri) ?: 0L
            val jobId = upload.enqueue(SafUploadSource(context, uri, size), folderId, name, mime)
            val fileId = db.transferJobDao().getById(jobId)?.fileId
            if (fileId == null) {
                db.transferJobDao().delete(jobId)
                return@mapNotNull null
            }
            UploadSourceStore.saveSaf(db, fileId, uri, size)
            jobId
        }.getOrNull()
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()

    private fun querySize(resolver: android.content.ContentResolver, uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else null
        }
    }.getOrNull()
}
