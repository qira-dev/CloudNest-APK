package com.qiradev.cloudnest.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qiradev.cloudnest.R
import com.qiradev.cloudnest.data.entity.FileEntity
import com.qiradev.cloudnest.data.entity.FolderEntity
import java.util.Locale

/** Human-readable byte size — archive rows are metadata-only (specs/01 §0). */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

/**
 * Archive browse screen (specs/09 screens 3+4): folder tree with breadcrumbs,
 * every state (loading/error/empty/content), long-press trash action. The upload
 * FAB arrives with the SAF picker wiring (step 7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    state: FolderUiState,
    onOpenFolder: (String) -> Unit,
    onOpenRoot: () -> Unit,
    onOpenTrash: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onTrashFolder: (String) -> Unit,
    onTrashFile: (String) -> Unit,
    onRetry: () -> Unit,
) {
    var showNewFolderDialog by remember { mutableStateOf(false) }
    val createFolderLabel = stringResource(R.string.cd_create_folder)
    val openTrashLabel = stringResource(R.string.cd_open_trash)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val crumbs = (state as? FolderUiState.Ready)?.breadcrumbs
                    Text(
                        text = crumbs?.lastOrNull()?.name?.ifBlank { stringResource(R.string.screen_archive) }
                            ?: stringResource(R.string.screen_archive),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(
                        onClick = { showNewFolderDialog = true },
                        modifier = Modifier.semantics { contentDescription = createFolderLabel },
                    ) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = null)
                    }
                    IconButton(
                        onClick = onOpenTrash,
                        modifier = Modifier.semantics { contentDescription = openTrashLabel },
                    ) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state is FolderUiState.Ready) {
                BreadcrumbsRow(
                    crumbs = state.breadcrumbs,
                    onOpenRoot = onOpenRoot,
                    onOpenFolder = onOpenFolder,
                )
                HorizontalDivider()
            }
            when (state) {
                is FolderUiState.Loading -> Centered { CircularProgressIndicator() }
                is FolderUiState.Error -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.label_error_generic))
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                    }
                }
                is FolderUiState.Ready -> {
                    if (state.folders.isEmpty() && state.files.isEmpty()) {
                        Centered { Text(stringResource(R.string.label_empty_folder)) }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.folders, key = { "folder-${it.id}" }) { folder ->
                                FolderRow(folder, onOpenFolder, onTrashFolder)
                                HorizontalDivider()
                            }
                            items(state.files, key = { "file-${it.id}" }) { file ->
                                FileRow(file, onTrashFile)
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewFolderDialog) {
        NewFolderDialog(
            onDismiss = { showNewFolderDialog = false },
            onCreate = { name ->
                showNewFolderDialog = false
                onCreateFolder(name)
            },
        )
    }
}

@Composable
private fun BreadcrumbsRow(
    crumbs: List<FolderEntity>,
    onOpenRoot: () -> Unit,
    onOpenFolder: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onOpenRoot) {
            Text(
                stringResource(R.string.screen_archive),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        crumbs.forEach { crumb ->
            if (crumb.name.isEmpty()) return@forEach // root itself is never listed (specs/01 §1.4)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            TextButton(onClick = { onOpenFolder(crumb.id) }) {
                Text(crumb.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    folder: FolderEntity,
    onOpenFolder: (String) -> Unit,
    onTrash: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val folderLabel = stringResource(R.string.cd_folder_icon)
    val moveToTrashLabel = stringResource(R.string.action_move_to_trash)
    ListItem(
        headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(Icons.Filled.Folder, contentDescription = folderLabel) },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onOpenFolder(folder.id) },
                onLongClick = { menuOpen = true },
            ),
        trailingContent = {
            Box {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(moveToTrashLabel) },
                        onClick = {
                            menuOpen = false
                            onTrash(folder.id)
                        },
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: FileEntity,
    onTrash: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val fileLabel = stringResource(R.string.cd_file_icon)
    val moveToTrashLabel = stringResource(R.string.action_move_to_trash)
    ListItem(
        headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text("${formatBytes(file.sizeBytes)} · ${file.mimeType}", maxLines = 1)
        },
        leadingContent = { Icon(Icons.Filled.Description, contentDescription = fileLabel) },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
        trailingContent = {
            Box {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(moveToTrashLabel) },
                        onClick = {
                            menuOpen = false
                            onTrash(file.id)
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_new_folder_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.dialog_folder_name_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        content()
    }
}
