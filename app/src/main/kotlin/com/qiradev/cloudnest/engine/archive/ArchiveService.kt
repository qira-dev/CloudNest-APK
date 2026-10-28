package com.qiradev.cloudnest.engine.archive

import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.SettingsKeys
import com.qiradev.cloudnest.data.entity.AuditEventEntity
import com.qiradev.cloudnest.data.entity.AuditEventType
import com.qiradev.cloudnest.data.entity.DeletionAttemptEntity
import com.qiradev.cloudnest.data.entity.FileEntity
import com.qiradev.cloudnest.data.entity.FolderEntity
import com.qiradev.cloudnest.data.entity.JobKind
import com.qiradev.cloudnest.data.entity.JobStatus
import com.qiradev.cloudnest.data.entity.NotificationEntity
import com.qiradev.cloudnest.data.entity.NotificationType
import com.qiradev.cloudnest.data.entity.TransferJobEntity
import com.qiradev.cloudnest.engine.providers.ProviderConnector
import com.qiradev.cloudnest.engine.providers.ProviderErrorCode
import com.qiradev.cloudnest.engine.providers.ProviderException
import com.qiradev.cloudnest.engine.upload.TokenProvider
import java.io.IOException
import java.util.UUID

/** Safe error codes carried by purge failures (specs/02 §8 style — no provider internals). */
object ArchiveErrorCode {
    const val NETWORK_UNREACHABLE = "network_unreachable"
    const val PROVIDER_DOWN = "provider_down"
    const val UNKNOWN = "unknown"
}

/** Outcome of one [ArchiveService.purgeDue] pass (diagnostics + trash_purged notification). */
data class PurgeSummary(
    val purgedFiles: Int,
    val failedFiles: Int,
    val purgedFolders: Int,
    val deletedRemoteObjects: Int,
)

/**
 * Folder tree + trash (specs/01 §1.4–§1.5, §1.11; specs/09 screens 3/4/11).
 *
 * Deleting marks `deleted_at`/`purge_at = now + trash_retention_days` on the folder
 * subtree and every contained file; restore clears the marks (a file restore also
 * revives its trashed ancestor chain so the file is reachable again). Purge deletes
 * every chunk placement's cloud object through the provider connector, recording
 * per-file `deletion_attempts` rows so a retry never deletes a remote object twice;
 * only fully cleared files lose their row.
 */
class ArchiveService(
    private val db: CloudNestDatabase,
    private val connectors: Map<String, ProviderConnector>,
    private val tokens: TokenProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // ---- folder tree (specs/01 §1.4) ----

    /** The archive root (parent_id = NULL, name = "") — created on first use, never listed. */
    suspend fun ensureRootFolder(): FolderEntity =
        db.folderDao().getRoot() ?: run {
            val id = UUID.randomUUID().toString()
            val root = FolderEntity(id, null, "", clock(), null, null)
            db.folderDao().insert(root)
            root
        }

    /** Creates a folder; the root itself (parent_id = NULL, name = "") is pre-created. */
    suspend fun createFolder(parentId: String?, name: String): String {
        require(name.isNotBlank()) { "folder name must not be blank" }
        parentId?.let { pid ->
            val parent = requireNotNull(db.folderDao().getById(pid)) { "no folder: $pid" }
            check(parent.deletedAt == null) { "parent folder is trashed" }
        }
        val id = UUID.randomUUID().toString()
        db.folderDao().insert(FolderEntity(id, parentId, name.trim(), clock(), null, null))
        return id
    }

    /** Root-first chain from the root to [folderId]; trashed folders break the walk. */
    suspend fun breadcrumbs(folderId: String): List<FolderEntity> {
        val chain = ArrayList<FolderEntity>()
        var current = db.folderDao().getById(folderId)
        while (current != null && current.deletedAt == null) {
            chain += current
            current = current.parentId?.let { db.folderDao().getById(it) }
        }
        return chain.asReversed()
    }

    // ---- trash (specs/09 screen 11) ----

    suspend fun trashFile(fileId: String) {
        val file = db.fileDao().getById(fileId) ?: return
        if (file.deletedAt != null) return
        val now = clock()
        db.fileDao().update(file.copy(deletedAt = now, purgeAt = now + retentionMillis()))
        audit(AuditEventType.FILE_DELETED, """{"file_id":"$fileId","name":"${jsonEscape(file.name)}"}""")
    }

    /** Trashing a folder trashes the whole subtree — every subfolder and contained file. */
    suspend fun trashFolder(folderId: String) {
        val folder = db.folderDao().getById(folderId) ?: return
        if (folder.deletedAt != null) return
        val now = clock()
        val purgeAt = now + retentionMillis()
        val subtree = db.folderDao().listAll().let { all ->
            descendantsOf(folderId, all.associateBy { it.id }) + folderId
        }
        subtree.mapNotNull { db.folderDao().getById(it) }
            .filter { it.deletedAt == null }
            .forEach { db.folderDao().update(it.copy(deletedAt = now, purgeAt = purgeAt)) }
        db.fileDao().listByFolders(subtree)
            .filter { it.deletedAt == null }
            .forEach { db.fileDao().update(it.copy(deletedAt = now, purgeAt = purgeAt)) }
        audit(AuditEventType.FILE_DELETED, """{"folder_id":"$folderId","name":"${jsonEscape(folder.name)}"}""")
    }

    /** Restores the file and, when its ancestor chain is trashed, that chain too. */
    suspend fun restoreFile(fileId: String) {
        val file = db.fileDao().getById(fileId) ?: return
        if (file.deletedAt == null) return
        restoreAncestorChain(file.folderId)
        db.fileDao().update(file.copy(deletedAt = null, purgeAt = null, updatedAt = clock()))
        audit(AuditEventType.FILE_RESTORED, """{"file_id":"$fileId"}""")
    }

    /** Restores the whole subtree — folders and every file inside. */
    suspend fun restoreFolder(folderId: String) {
        val folder = db.folderDao().getById(folderId) ?: return
        if (folder.deletedAt == null) return
        restoreAncestorChain(folder.parentId)
        val subtree = db.folderDao().listAll().let { all ->
            descendantsOf(folderId, all.associateBy { it.id }) + folderId
        }
        subtree.mapNotNull { db.folderDao().getById(it) }
            .filter { it.deletedAt != null }
            .forEach { db.folderDao().update(it.copy(deletedAt = null, purgeAt = null)) }
        db.fileDao().listByFolders(subtree)
            .filter { it.deletedAt != null }
            .forEach { db.fileDao().update(it.copy(deletedAt = null, purgeAt = null, updatedAt = clock())) }
        audit(AuditEventType.FILE_RESTORED, """{"folder_id":"$folderId"}""")
    }

    // ---- purge (specs/01 §1.11) ----

    /**
     * One idempotent purge pass over everything whose retention window elapsed.
     * Files: cloud objects deleted with a ledger row each; only fully cleared
     * files lose their row. Folders go once their subtree holds no rows.
     */
    suspend fun purgeDue(now: Long = clock()): PurgeSummary {
        var purgedFiles = 0
        var failedFiles = 0
        var deletedRemote = 0
        db.fileDao().listPurgeDue(now).forEach { file ->
            val result = purgeFile(file)
            if (result.success) {
                purgedFiles++
                deletedRemote += result.deletedRemoteObjects
            } else {
                failedFiles++
            }
        }

        var purgedFolders = 0
        db.folderDao().listPurgeDue(now).forEach { folder ->
            if (purgeFolderIfCleared(folder)) purgedFolders++
        }

        if (purgedFiles > 0) {
            notify(NotificationType.TRASH_PURGED, """{"count":$purgedFiles}""")
        }
        return PurgeSummary(purgedFiles, failedFiles, purgedFolders, deletedRemote)
    }

    /** "এখনই চিরতরে মুছা / খালি করা" (specs/09 screen 11): purge everything trashed immediately. */
    suspend fun emptyTrash(): PurgeSummary {
        val now = clock()
        db.fileDao().listTrashed().forEach { file ->
            db.fileDao().update(file.copy(purgeAt = now))
        }
        db.folderDao().listTrashed().forEach { folder ->
            db.folderDao().update(folder.copy(purgeAt = now))
        }
        return purgeDue(now)
    }

    /** "এখনই চিরতরে মুছা" on one file (specs/09 screen 11): trash if needed, then purge at once. */
    suspend fun deleteFileForever(fileId: String): Boolean {
        val file = db.fileDao().getById(fileId) ?: return false
        if (file.deletedAt == null) trashFile(fileId)
        val now = clock()
        db.fileDao().update(db.fileDao().getById(fileId)!!.copy(purgeAt = now))
        return purgeFile(db.fileDao().getById(fileId)!!).success
    }

    /** "এখনই চিরতরে মুছা" on one folder: trash the subtree, then purge everything now. */
    suspend fun deleteFolderForever(folderId: String): Boolean {
        val folder = db.folderDao().getById(folderId) ?: return false
        if (folder.deletedAt == null) trashFolder(folderId)
        val now = clock()
        val all = db.folderDao().listAll()
        val byId = all.associateBy { it.id }
        val subtree = descendantsOf(folderId, byId) + folderId
        subtree.mapNotNull { db.folderDao().getById(it) }.forEach {
            db.folderDao().update(it.copy(purgeAt = now))
        }
        db.fileDao().listByFolders(subtree).forEach {
            db.fileDao().update(it.copy(purgeAt = now))
        }
        val summary = purgeDue(now)
        return summary.failedFiles == 0
    }

    /** Purges one due file: ledger → cloud deletions → row removal. Retries skip done remote ids. */
    private suspend fun purgeFile(file: FileEntity): PurgeFileResult {
        val now = clock()
        val jobId = UUID.randomUUID().toString()
        db.transferJobDao().insert(
            TransferJobEntity(
                id = jobId,
                kind = JobKind.PURGE,
                fileId = file.id,
                status = JobStatus.RUNNING,
                errorCode = null,
                attempts = 1,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val attemptId = UUID.randomUUID().toString()
        db.deletionAttemptDao().insert(
            DeletionAttemptEntity(
                id = attemptId,
                fileId = file.id,
                startedAt = now,
                finishedAt = null,
                result = null,
                errorCode = null,
                remoteIdsJson = null,
            ),
        )

        // Remote objects already confirmed deleted by earlier ledger rows are never re-deleted.
        val alreadyGone = db.deletionAttemptDao().listByFile(file.id)
            .filter { it.id != attemptId && it.result == DeletionResult.OK }
            .flatMap { decodeRemoteIds(it.remoteIdsJson) }
            .toMutableSet()

        var deletedHere = 0
        var lastError: String? = null
        val confirmed = ArrayList<String>(alreadyGone)
        db.chunkPlacementDao().listByFile(file.id)
            .filter { it.remoteId != null && it.remoteId !in alreadyGone }
            .forEach { placement ->
                val remoteId = placement.remoteId!!
                try {
                    deleteRemote(placement.accountId, remoteId)
                    confirmed += remoteId
                    deletedHere++
                } catch (e: Exception) {
                    lastError = errorCodeOf(e)
                }
            }

        return if (lastError == null) {
            finishAttempt(attemptId, DeletionResult.OK, null, confirmed)
            finishJob(jobId, JobStatus.DONE, null)
            db.fileDao().delete(file.id) // cascades chunks → placements → attempts ledger
            audit(AuditEventType.FILE_PURGED, """{"file_id":"${file.id}"}""")
            PurgeFileResult(success = true, deletedRemoteObjects = deletedHere)
        } else {
            finishAttempt(attemptId, DeletionResult.ERROR, lastError, confirmed)
            finishJob(jobId, JobStatus.FAILED, lastError)
            PurgeFileResult(success = false, deletedRemoteObjects = deletedHere)
        }
    }

    /** A trashed folder is purged only when its whole subtree holds no file rows. */
    private suspend fun purgeFolderIfCleared(folder: FolderEntity): Boolean {
        val all = db.folderDao().listAll()
        val byId = all.associateBy { it.id }
        val subtree = descendantsOf(folder.id, byId) + folder.id
        if (db.fileDao().listByFolders(subtree).isNotEmpty()) return false
        if (subtree.mapNotNull { byId[it] }.any { it.deletedAt == null }) return false
        // Bottom-up so the RESTRICT self-FK never bites.
        subtree.sortedByDescending { depthInTree(it, byId) }.forEach { id ->
            db.folderDao().delete(id)
        }
        return true
    }

    // ---- helpers ----

    private suspend fun restoreAncestorChain(folderId: String?) {
        var folder = folderId?.let { db.folderDao().getById(it) }
        while (folder != null && folder.deletedAt != null) {
            db.folderDao().update(folder.copy(deletedAt = null, purgeAt = null))
            folder = folder.parentId?.let { db.folderDao().getById(it) }
        }
    }

    private suspend fun deleteRemote(accountId: String, remoteId: String) {
        val account = requireNotNull(db.providerAccountDao().getById(accountId)) { "no account: $accountId" }
        val connector = requireNotNull(connectors[account.provider]) { "no connector: ${account.provider}" }
        connector.deleteObject(tokens.validTokens(account.id), remoteId)
    }

    private suspend fun finishAttempt(id: String, result: String, errorCode: String?, remoteIds: List<String>) {
        db.deletionAttemptDao().getById(id)?.let { row ->
            db.deletionAttemptDao().update(
                row.copy(
                    finishedAt = clock(),
                    result = result,
                    errorCode = errorCode,
                    remoteIdsJson = encodeRemoteIds(remoteIds),
                ),
            )
        }
    }

    private suspend fun finishJob(jobId: String, status: String, errorCode: String?) {
        db.transferJobDao().getById(jobId)?.let { job ->
            db.transferJobDao().update(job.copy(status = status, errorCode = errorCode, updatedAt = clock()))
        }
    }

    private suspend fun retentionMillis(): Long {
        val days = db.settingsDao().getValue(SettingsKeys.TRASH_RETENTION_DAYS)?.toIntOrNull() ?: 7
        return days.toLong() * 24L * 60L * 60L * 1000L
    }

    private suspend fun audit(type: String, detailJson: String) {
        db.auditEventDao().insert(
            AuditEventEntity(UUID.randomUUID().toString(), type, detailJson, clock()),
        )
        db.auditEventDao().trimTo(500)
    }

    private suspend fun notify(type: String, payload: String) {
        db.notificationDao().insert(
            NotificationEntity(UUID.randomUUID().toString(), type, payload, read = 0, createdAt = clock()),
        )
        db.notificationDao().trimTo(100)
    }

    private fun descendantsOf(rootId: String, byId: Map<String, FolderEntity>): List<String> {
        val childrenByParent = byId.values.filter { it.parentId != null }.groupBy { it.parentId!! }
        val out = ArrayList<String>()
        val queue = ArrayDeque(listOf(rootId))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            childrenByParent[current]?.forEach { child ->
                out += child.id
                queue += child.id
            }
        }
        return out
    }

    private fun depthInTree(id: String, byId: Map<String, FolderEntity>): Int {
        var depth = 0
        var current = byId[id]
        while (current?.parentId != null) {
            depth++
            current = byId[current.parentId]
        }
        return depth
    }

    private fun errorCodeOf(e: Throwable): String = when (e) {
        is ProviderException -> when (e.code) {
            ProviderErrorCode.PROVIDER_DOWN -> ArchiveErrorCode.PROVIDER_DOWN
            ProviderErrorCode.PROVIDER_RATE_LIMITED -> ArchiveErrorCode.PROVIDER_DOWN
            else -> ArchiveErrorCode.UNKNOWN
        }
        is IOException -> ArchiveErrorCode.NETWORK_UNREACHABLE
        else -> ArchiveErrorCode.UNKNOWN
    }

    private fun jsonEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    private data class PurgeFileResult(val success: Boolean, val deletedRemoteObjects: Int)

    private companion object {
        fun encodeRemoteIds(ids: List<String>): String =
            ids.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }

        fun decodeRemoteIds(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            return Regex("\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        }
    }

    private object DeletionResult {
        const val OK = "ok"
        const val ERROR = "error"
    }
}
