package com.qiradev.cloudnest.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.qiradev.cloudnest.R
import com.qiradev.cloudnest.engine.security.Backup
import java.text.DateFormat
import java.util.Date

/** Chunk sizes the engine allows (specs/02 §1). */
val CHUNK_SIZE_OPTIONS = listOf(8, 16, 32, 64)

/** Trash-retention choices (default 7 = one week, specs/01 §1.2). */
val TRASH_RETENTION_OPTIONS = listOf(1, 7, 14, 30)

/**
 * Settings screen (specs/09 §13; step-8 scope: upload/storage policy + backup).
 * Wi-Fi-only, chunk size, mirroring and trash retention write through the
 * controller (each audited); backup export/import run the specs/08 §4 flow
 * with its confirmation dialogs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    busy: Boolean,
    event: BackupUiEvent?,
    onBack: () -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onChunkSize: (Int) -> Unit,
    onMirror: (Boolean) -> Unit,
    onTrashRetention: (Int) -> Unit,
    onBackupReminder: (Boolean) -> Unit,
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
    onClearEvent: () -> Unit,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showChunkDialog by remember { mutableStateOf(false) }
    var showRetentionDialog by remember { mutableStateOf(false) }
    var restoreDone by remember { mutableStateOf(false) }

    LaunchedEffect(event) {
        when (event) {
            is BackupUiEvent.ExportDone -> {
                snackbar.showSnackbar(context.getString(R.string.backup_export_done))
                onClearEvent()
            }
            is BackupUiEvent.BackupFailed -> {
                val reason = event.reason.substringAfter("invalid backup: ")
                val invalid = event.reason.startsWith("invalid backup: ")
                snackbar.showSnackbar(
                    context.getString(
                        if (invalid) R.string.backup_invalid else R.string.backup_failed,
                        reason,
                    ),
                )
                onClearEvent()
            }
            BackupUiEvent.RestoreDone -> {
                restoreDone = true
                onClearEvent()
            }
            null -> {}
        }
    }

    val exportTarget = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(Backup.BACKUP_MIME),
    ) { uri -> if (uri != null) onExport(uri) }

    val importSource = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onImport(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (busy) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) { CircularProgressIndicator() }
            }

            // ---- weekly backup reminder (specs/08 §4.3) ----
            val due = state.backupReminderEnabled && System.currentTimeMillis() - state.lastBackupAt >
                WEEK_MS
            if (due) {
                Text(
                    stringResource(R.string.backup_reminder_due),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            SettingsGroupTitle(stringResource(R.string.group_upload))
            SwitchRow(
                title = stringResource(R.string.setting_wifi_only),
                subtitle = stringResource(R.string.setting_wifi_only_hint),
                checked = state.wifiOnlyUpload,
                onChecked = onWifiOnly,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.setting_chunk_size)) },
                supportingContent = { Text(stringResource(R.string.chunk_size_value, state.chunkSizeMib)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showChunkDialog = true },
            )
            SwitchRow(
                title = stringResource(R.string.setting_mirror),
                subtitle = stringResource(R.string.setting_mirror_hint),
                checked = state.mirrorEnabled,
                onChecked = onMirror,
            )
            HorizontalDivider()

            SettingsGroupTitle(stringResource(R.string.group_trash))
            ListItem(
                headlineContent = { Text(stringResource(R.string.setting_trash_retention)) },
                supportingContent = { Text(stringResource(R.string.trash_retention_value, state.trashRetentionDays)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showRetentionDialog = true },
            )
            HorizontalDivider()

            SettingsGroupTitle(stringResource(R.string.group_backup))
            Text(
                text = if (state.lastBackupAt > 0) {
                    val formatted = DateFormat.getDateTimeInstance().format(Date(state.lastBackupAt))
                    stringResource(R.string.backup_last_at, formatted)
                } else {
                    stringResource(R.string.backup_never)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            SwitchRow(
                title = stringResource(R.string.backup_reminder),
                subtitle = null,
                checked = state.backupReminderEnabled,
                onChecked = onBackupReminder,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.action_export_backup)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showExportDialog = true },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.action_import_backup)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showImportDialog = true },
            )
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.dialog_backup_export_title)) },
            text = { Text(stringResource(R.string.dialog_backup_export_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        exportTarget.launch(Backup.backupFileName())
                    },
                ) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text(stringResource(R.string.dialog_backup_import_title)) },
            text = { Text(stringResource(R.string.dialog_backup_import_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportDialog = false
                        importSource.launch(arrayOf("*/*"))
                    },
                ) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showChunkDialog) {
        RadioDialog(
            title = stringResource(R.string.setting_chunk_size),
            options = CHUNK_SIZE_OPTIONS.map { stringResource(R.string.chunk_size_value, it) },
            selectedIndex = CHUNK_SIZE_OPTIONS.indexOf(state.chunkSizeMib),
            onSelect = { index ->
                showChunkDialog = false
                onChunkSize(CHUNK_SIZE_OPTIONS[index])
            },
            onDismiss = { showChunkDialog = false },
        )
    }

    if (showRetentionDialog) {
        RadioDialog(
            title = stringResource(R.string.setting_trash_retention),
            options = TRASH_RETENTION_OPTIONS.map { stringResource(R.string.trash_retention_value, it) },
            selectedIndex = TRASH_RETENTION_OPTIONS.indexOf(state.trashRetentionDays),
            onSelect = { index ->
                showRetentionDialog = false
                onTrashRetention(TRASH_RETENTION_OPTIONS[index])
            },
            onDismiss = { showRetentionDialog = false },
        )
    }

    if (restoreDone) {
        // The database was replaced under the running process — restart cleanly
        // so every component reopens the restored file (specs/08 §4.2 note).
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.screen_settings)) },
            text = { Text(stringResource(R.string.backup_restore_done)) },
            confirmButton = {
                TextButton(onClick = { restartApp(context) }) {
                    Text(stringResource(R.string.action_continue))
                }
            },
        )
    }
}

private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

/** Relaunches the app task and ends the current process (restore-restart). */
fun restartApp(context: android.content.Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(launch)
    Runtime.getRuntime().exit(0)
}

@Composable
private fun SettingsGroupTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    val switchLabel = title
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        modifier = Modifier.semantics { contentDescription = switchLabel },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onChecked)
        },
    )
}

@Composable
private fun RadioDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 8.dp),
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = index == selectedIndex,
                            onClick = { onSelect(index) },
                        )
                        Text(option)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
