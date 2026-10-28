package com.qiradev.cloudnest.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.qiradev.cloudnest.R
import com.qiradev.cloudnest.data.entity.FileEntity
import com.qiradev.cloudnest.data.entity.FolderEntity

private enum class TrashConfirm { NONE, DELETE_FILE, DELETE_FOLDER, EMPTY_TRASH }

/**
 * Trash screen (specs/09 screen 11): trashed items with days left, restore,
 * delete-forever (guarded by a confirmation), empty-trash.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    state: TrashUiState,
    onBack: () -> Unit,
    onRestoreFile: (String) -> Unit,
    onRestoreFolder: (String) -> Unit,
    onDeleteFileForever: (String) -> Unit,
    onDeleteFolderForever: (String) -> Unit,
    onEmptyTrash: () -> Unit,
    onRetry: () -> Unit,
) {
    var confirm by remember { mutableStateOf(TrashConfirm.NONE) }
    var pendingFileId by remember { mutableStateOf<String?>(null) }
    var pendingFolderId by remember { mutableStateOf<String?>(null) }
    val emptyTrashLabel = stringResource(R.string.cd_empty_trash)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_trash)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_cancel)) }
                },
                actions = {
                    IconButton(
                        onClick = { confirm = TrashConfirm.EMPTY_TRASH },
                        modifier = Modifier.semantics { contentDescription = emptyTrashLabel },
                    ) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                is TrashUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
                is TrashUiState.Error -> androidx.compose.foundation.layout.Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.label_error_generic))
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                }
                is TrashUiState.Ready -> {
                    if (state.folders.isEmpty() && state.files.isEmpty()) {
                        Text(
                            stringResource(R.string.label_empty_trash),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.folders, key = { "folder-${it.id}" }) { folder ->
                                TrashedFolderRow(
                                    folder = folder,
                                    daysLeft = state.daysLeft(folder.purgeAt),
                                    onRestore = { onRestoreFolder(folder.id) },
                                    onDeleteForever = {
                                        pendingFolderId = folder.id
                                        confirm = TrashConfirm.DELETE_FOLDER
                                    },
                                )
                                HorizontalDivider()
                            }
                            items(state.files, key = { "file-${it.id}" }) { file ->
                                TrashedFileRow(
                                    file = file,
                                    daysLeft = state.daysLeft(file.purgeAt),
                                    onRestore = { onRestoreFile(file.id) },
                                    onDeleteForever = {
                                        pendingFileId = file.id
                                        confirm = TrashConfirm.DELETE_FILE
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    when (confirm) {
        TrashConfirm.NONE -> Unit
        TrashConfirm.DELETE_FILE -> ConfirmDialog(
            onConfirm = {
                confirm = TrashConfirm.NONE
                pendingFileId?.let(onDeleteFileForever)
                pendingFileId = null
            },
            onDismiss = { confirm = TrashConfirm.NONE },
        )
        TrashConfirm.DELETE_FOLDER -> ConfirmDialog(
            onConfirm = {
                confirm = TrashConfirm.NONE
                pendingFolderId?.let(onDeleteFolderForever)
                pendingFolderId = null
            },
            onDismiss = { confirm = TrashConfirm.NONE },
        )
        TrashConfirm.EMPTY_TRASH -> ConfirmDialog(
            onConfirm = {
                confirm = TrashConfirm.NONE
                onEmptyTrash()
            },
            onDismiss = { confirm = TrashConfirm.NONE },
        )
    }
}

@Composable
private fun TrashedFileRow(
    file: FileEntity,
    daysLeft: Int,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    val fileLabel = stringResource(R.string.cd_file_icon)
    val restoreLabel = stringResource(R.string.cd_restore)
    val deleteLabel = stringResource(R.string.cd_delete_forever)
    ListItem(
        headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(daysLeftText(daysLeft)) },
        leadingContent = { Icon(Icons.Filled.Description, contentDescription = fileLabel) },
        trailingContent = {
            androidx.compose.foundation.layout.Row {
                IconButton(onClick = onRestore, modifier = Modifier.semantics { contentDescription = restoreLabel }) {
                    Icon(Icons.Filled.RestoreFromTrash, contentDescription = null)
                }
                IconButton(onClick = onDeleteForever, modifier = Modifier.semantics { contentDescription = deleteLabel }) {
                    Icon(Icons.Filled.DeleteForever, contentDescription = null)
                }
            }
        },
    )
}

@Composable
private fun TrashedFolderRow(
    folder: FolderEntity,
    daysLeft: Int,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    val folderLabel = stringResource(R.string.cd_folder_icon)
    val restoreLabel = stringResource(R.string.cd_restore)
    val deleteLabel = stringResource(R.string.cd_delete_forever)
    ListItem(
        headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(daysLeftText(daysLeft)) },
        leadingContent = { Icon(Icons.Filled.Folder, contentDescription = folderLabel) },
        trailingContent = {
            androidx.compose.foundation.layout.Row {
                IconButton(onClick = onRestore, modifier = Modifier.semantics { contentDescription = restoreLabel }) {
                    Icon(Icons.Filled.RestoreFromTrash, contentDescription = null)
                }
                IconButton(onClick = onDeleteForever, modifier = Modifier.semantics { contentDescription = deleteLabel }) {
                    Icon(Icons.Filled.DeleteForever, contentDescription = null)
                }
            }
        },
    )
}

@Composable
private fun daysLeftText(daysLeft: Int): String =
    if (daysLeft <= 0) {
        stringResource(R.string.trash_purges_today)
    } else {
        stringResource(R.string.trash_purges_in_days, daysLeft)
    }

@Composable
private fun ConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_delete_forever_title)) },
        text = { Text(stringResource(R.string.dialog_empty_trash_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete_forever)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
