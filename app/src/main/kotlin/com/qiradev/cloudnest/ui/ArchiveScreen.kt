package com.qiradev.cloudnest.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.qiradev.cloudnest.AppServices
import com.qiradev.cloudnest.R
import com.qiradev.cloudnest.background.ShareUploads
import com.qiradev.cloudnest.background.UploadForegroundService
import com.qiradev.cloudnest.data.SettingsKeys
import com.qiradev.cloudnest.data.entity.FileEntity
import com.qiradev.cloudnest.data.entity.FolderEntity
import java.util.Locale
import kotlinx.coroutines.launch

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
 * every state (loading/error/empty/content), long-press trash action, the
 * per-account health strip (specs/06 §2.3) and the upload FAB wired to the SAF
 * picker (specs/05 §5) — notification permission (§3.1) and the one-time
 * battery-exemption explainer (§3.2) come first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    state: FolderUiState,
    accountHealth: List<AccountHealthUi>,
    onOpenFolder: (String) -> Unit,
    onOpenRoot: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onTrashFolder: (String) -> Unit,
    onTrashFile: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppServices.db(context) }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    val createFolderLabel = stringResource(R.string.cd_create_folder)
    val openTrashLabel = stringResource(R.string.cd_open_trash)
    val openSettingsLabel = stringResource(R.string.cd_open_settings)
    val uploadLabel = stringResource(R.string.cd_upload_fab)

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val rootId = AppServices.archiveService(db).ensureRootFolder().id
            // Settings-aware enqueue: the stored chunk size lands on the file row.
            val (upload, _) = AppServices.liveTransferServices(context, db)
            ShareUploads.enqueueFromSafUris(context, db, upload, uris, rootId)
            UploadForegroundService.start(context)
        }
    }

    val continueAfterPermission: () -> Unit = {
        scope.launch {
            val batteryAsked = db.settingsDao().getValue(SettingsKeys.BATTERY_EXEMPTION_ASKED) != null
            if (batteryAsked) {
                pickFiles.launch(arrayOf("*/*"))
            } else {
                showBatteryDialog = true
            }
        }
    }

    val requestNotificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { continueAfterPermission() }

    val beginUpload: () -> Unit = {
        // specs/05 §3.1: asked once before the first upload; a refusal keeps the app working.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            continueAfterPermission()
        }
    }

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
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.semantics { contentDescription = openSettingsLabel },
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = beginUpload,
                modifier = Modifier.semantics { contentDescription = uploadLabel },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (accountHealth.isNotEmpty()) {
                AccountHealthStrip(
                    accounts = accountHealth,
                    onOpenDiagnostics = onOpenDiagnostics,
                )
                HorizontalDivider()
            }
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
                                FileRow(file, onOpenFile, onTrashFile)
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

    if (showBatteryDialog) {
        // specs/05 §3.2 — explained once; refusing still uploads (revive catches pauses).
        AlertDialog(
            onDismissRequest = {
                showBatteryDialog = false
                pickFiles.launch(arrayOf("*/*"))
            },
            title = { Text(stringResource(R.string.dialog_battery_title)) },
            text = { Text(stringResource(R.string.dialog_battery_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            db.settingsDao().upsert(
                                com.qiradev.cloudnest.data.entity.SettingsEntity(
                                    SettingsKeys.BATTERY_EXEMPTION_ASKED,
                                    "1",
                                ),
                            )
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                        .setData(Uri.parse("package:${context.packageName}")),
                                )
                            }
                        }
                        showBatteryDialog = false
                        pickFiles.launch(arrayOf("*/*"))
                    },
                ) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            db.settingsDao().upsert(
                                com.qiradev.cloudnest.data.entity.SettingsEntity(
                                    SettingsKeys.BATTERY_EXEMPTION_ASKED,
                                    "1",
                                ),
                            )
                        }
                        showBatteryDialog = false
                        pickFiles.launch(arrayOf("*/*"))
                    },
                ) { Text(stringResource(R.string.action_not_now)) }
            },
        )
    }
}

/**
 * Home health colors (specs/06 §2.3): one chip per account — colored dot
 * (green/yellow/red) + provider label; tapping opens the diagnostics screen.
 */
@Composable
private fun AccountHealthStrip(
    accounts: List<AccountHealthUi>,
    onOpenDiagnostics: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        accounts.forEach { account ->
            val verdictLabel = stringResource(account.verdict.labelRes())
            androidx.compose.material3.AssistChip(
                onClick = onOpenDiagnostics,
                label = { Text(account.provider.removeSuffix("_drive").replace('_', ' '), maxLines = 1) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(account.verdict.color(), androidx.compose.foundation.shape.CircleShape)
                            .semantics { contentDescription = verdictLabel },
                    )
                },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
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
    onOpen: (String) -> Unit,
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
            .combinedClickable(onClick = { onOpen(file.id) }, onLongClick = { menuOpen = true }),
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
