package com.qiradev.cloudnest.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.entity.FileEntity
import com.qiradev.cloudnest.data.entity.FolderEntity
import com.qiradev.cloudnest.engine.archive.ArchiveService
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

/**
 * Drives the archive + trash screens (specs/09 screens 3/4/11) on top of
 * [ArchiveService]. Pure state holder — no Android imports — so the flows are
 * unit-testable exactly like the engines. Every mutation refreshes the state it
 * affects; errors surface as [FolderUiState.Error]/[TrashUiState.Error].
 */
class ArchiveUiController(
    private val db: CloudNestDatabase,
    private val archive: ArchiveService,
    private val scope: CoroutineScope,
) {
    var folderState: FolderUiState by mutableStateOf(FolderUiState.Loading)
        private set

    var trashState: TrashUiState by mutableStateOf(TrashUiState.Loading)
        private set

    /** Bumped after every mutation so screens can react without polling. */
    var revision: Int by mutableIntStateOf(0)
        private set

    private var currentFolderId: String? = null

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
}
