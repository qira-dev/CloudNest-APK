package com.qiradev.cloudnest.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.entity.FileEntity
import com.qiradev.cloudnest.data.entity.FolderEntity
import com.qiradev.cloudnest.data.entity.PlacementRole
import com.qiradev.cloudnest.data.entity.PlacementStatus
import com.qiradev.cloudnest.engine.archive.ArchiveService
import com.qiradev.cloudnest.engine.mirror.MirrorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One day in ms — trash "days left" math (specs/09 screen 11). */
private const val DAY_MS = 24L * 60L * 60L * 1000L

/** Browsing state of the archive/folder screen — every state a screen can sit in. */
sealed interface FolderUiState {
    data object Loading : FolderUiState
    data class Error(val message: String) : FolderUiState
    data class Ready(
        val breadcrumbs: List<FolderEntity>,
        val folders: List<FolderEntity>,
        val files: List<FileEntity>,
    ) : FolderUiState
}

/** Trash screen state (specs/09 screen 11). */
sealed interface TrashUiState {
    data object Loading : TrashUiState
    data class Error(val message: String) : TrashUiState
    data class Ready(
        val folders: List<FolderEntity>,
        val files: List<FileEntity>,
        val nowMs: Long,
    ) : TrashUiState {

        fun daysLeft(purgeAt: Long?): Int {
            purgeAt ?: return 0
            return (((purgeAt - nowMs) + DAY_MS - 1) / DAY_MS).toInt().coerceAtLeast(0)
        }
    }
}

/** Protection badge on the file detail screen (specs/06 §1.3). */
enum class ProtectionBadge { PROTECTED, MIRROR_PENDING, MIRROR_FAILED }

/** One chunk's copies as shown on the detail screen (specs/09 screen 5). */
data class CopyRow(
    val chunkIndex: Int,
    val rows: List<CopyPlacement>,
)

data class CopyPlacement(
    val accountLabel: String,
    val isMirror: Boolean,
    val verified: Boolean,
)

/** File detail state (specs/09 screen 5). */
sealed interface FileDetailUiState {
    data object Loading : FileDetailUiState
    data class Error(val message: String) : FileDetailUiState
    data class Ready(
        val file: FileEntity,
        val copies: List<CopyRow>,
        val badge: ProtectionBadge,
    ) : FileDetailUiState
}

/**
 * Drives the archive + trash screens (specs/09 screens 3/4/11) on top of
 * [ArchiveService]. Pure state holder — no Android imports — so the flows are
 * unit-testable exactly like the engines. Every mutation refreshes the state it
 * affects; errors surface as [FolderUiState.Error]/[TrashUiState.Error].
 */
class ArchiveUiController(
    private val db: CloudNestDatabase,
    private val archive: ArchiveService,
    private val mirror: MirrorService,
    private val scope: CoroutineScope,
) {
    var folderState: FolderUiState by mutableStateOf(FolderUiState.Loading)
        private set

    var trashState: TrashUiState by mutableStateOf(TrashUiState.Loading)
        private set

    var fileDetailState: FileDetailUiState by mutableStateOf(FileDetailUiState.Loading)
        private set

    /** Bumped after every mutation so screens can react without polling. */
    var revision: Int by mutableIntStateOf(0)
        private set

    private var currentFolderId: String? = null
    private var currentDetailFileId: String? = null

    /** Injectable wall clock — tests pin time instead of sleeping. */
    var clock: () -> Long = System::currentTimeMillis

    fun openFolder(folderId: String?) {
        currentFolderId = folderId
        refreshFolder()
    }

    fun refreshFolder() {
        scope.launch {
            folderState = try {
                val root = currentFolderId?.let { db.folderDao().getById(it) }
                    ?: archive.ensureRootFolder().also { currentFolderId = it.id }
                val crumbs = archive.breadcrumbs(root.id)
                FolderUiState.Ready(
                    breadcrumbs = crumbs,
                    folders = db.folderDao().listChildren(root.id),
                    files = db.fileDao().listByFolder(root.id),
                )
            } catch (e: Exception) {
                FolderUiState.Error(e.message ?: "unknown")
            }
        }
    }

    fun refreshTrash() {
        scope.launch {
            trashState = try {
                TrashUiState.Ready(
                    folders = db.folderDao().listTrashed(),
                    files = db.fileDao().listTrashed(),
                    nowMs = clock(),
                )
            } catch (e: Exception) {
                TrashUiState.Error(e.message ?: "unknown")
            }
        }
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        scope.launch {
            runCatching { archive.createFolder(currentFolderId, name.trim()) }
            refreshFolder()
        }
    }

    fun trashFile(fileId: String) {
        scope.launch {
            runCatching { archive.trashFile(fileId) }
            refreshFolder()
        }
    }

    fun trashFolder(folderId: String) {
        scope.launch {
            runCatching { archive.trashFolder(folderId) }
            refreshFolder()
        }
    }

    fun restoreFile(fileId: String) {
        scope.launch {
            runCatching { archive.restoreFile(fileId) }
            refreshTrash()
        }
    }

    fun restoreFolder(folderId: String) {
        scope.launch {
            runCatching { archive.restoreFolder(folderId) }
            refreshTrash()
        }
    }

    fun deleteFileForever(fileId: String) {
        scope.launch {
            runCatching { archive.deleteFileForever(fileId) }
            refreshTrash()
        }
    }

    fun deleteFolderForever(folderId: String) {
        scope.launch {
            runCatching { archive.deleteFolderForever(folderId) }
            refreshTrash()
        }
    }

    fun emptyTrash() {
        scope.launch {
            runCatching { archive.emptyTrash() }
            refreshTrash()
        }
    }

    // ---- file detail (specs/09 screen 5, specs/06 §1.3) ----

    fun openFileDetail(fileId: String) {
        currentDetailFileId = fileId
        refreshFileDetail()
    }

    fun refreshFileDetail() {
        val fileId = currentDetailFileId ?: return
        scope.launch {
            fileDetailState = try {
                val file = db.fileDao().getById(fileId) ?: error("no file: $fileId")
                val chunks = db.fileChunkDao().listByFile(fileId)
                val accounts = db.providerAccountDao().getAll().associateBy { it.id }
                val placementsByChunk = db.chunkPlacementDao().listByFile(fileId).groupBy { it.chunkId }
                var failed = false
                var pending = false
                val copies = chunks.map { chunk ->
                    val placements = placementsByChunk[chunk.id].orEmpty()
                    val mirrors = placements.filter { it.role == PlacementRole.MIRROR }
                    if (mirrors.any { it.status == PlacementStatus.FAILED }) failed = true
                    if (mirrors.none { it.status == PlacementStatus.VERIFIED }) pending = true
                    CopyRow(
                        chunkIndex = chunk.chunkIndex,
                        rows = placements
                            .filter { it.remoteId != null }
                            .map { placement ->
                                CopyPlacement(
                                    accountLabel = accounts[placement.accountId]?.label ?: placement.accountId,
                                    isMirror = placement.role == PlacementRole.MIRROR,
                                    verified = placement.status == PlacementStatus.VERIFIED,
                                )
                            },
                    )
                }
                FileDetailUiState.Ready(
                    file = file,
                    copies = copies,
                    badge = when {
                        failed -> ProtectionBadge.MIRROR_FAILED
                        pending -> ProtectionBadge.MIRROR_PENDING
                        else -> ProtectionBadge.PROTECTED
                    },
                )
            } catch (e: Exception) {
                FileDetailUiState.Error(e.message ?: "unknown")
            }
        }
    }

    /** "মিরর সম্পূর্ণ করো" (specs/06 §1.2): queues a fresh mirror job. */
    fun completeMirror() {
        val fileId = currentDetailFileId ?: return
        scope.launch {
            runCatching { mirror.enqueueJob(fileId) }
            refreshFileDetail()
        }
    }

    fun trashFromDetail() {
        val fileId = currentDetailFileId ?: return
        scope.launch {
            runCatching { archive.trashFile(fileId) }
            refreshFolder()
        }
    }
}
