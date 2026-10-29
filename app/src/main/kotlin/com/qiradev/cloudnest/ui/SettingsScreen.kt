package com.qiradev.cloudnest.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.Role
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

/** Routing strategies (specs/02 §3). */
val ROUTING_OPTIONS = listOf("capacity_aware" to R.string.routing_capacity_aware, "balanced" to R.string.routing_balanced)

/** Theme modes (specs/09 §13). */
val THEME_OPTIONS = listOf(
    "system" to R.string.theme_system,
    "light" to R.string.theme_light,
    "dark" to R.string.theme_dark,
)

/**
 * Settings screen (specs/09 §13): upload/storage policy, backup, language,
 * theme and the per-type phone-notification toggles — every change audited.
 * The visual template stands in for the owner's screenshots (specs/09).
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
    onRouting: (String) -> Unit,
    onMirror: (Boolean) -> Unit,
    onTrashRetention: (Int) -> Unit,
    onBackupReminder: (Boolean) -> Unit,
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
    onTheme: (String) -> Unit,
    onLanguage: (String) -> Unit,
    onPhoneNotify: (String, Boolean) -> Unit,
    onChangeUnlock: (String, String) -> Unit,
    onClearEvent: () -> Unit,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showChunkDialog by remember { mutableStateOf(false) }
    var showRetentionDialog by remember { mutableStateOf(false) }
    var showRoutingDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showUnlockDialog by remember { mutableStateOf(false) }
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
                        when {
                            invalid -> R.string.backup_invalid
                            reason == "wrong_password" -> R.string.unlock_wrong_password
                            else -> R.string.backup_failed
                        },
                        reason,
                    ),
                )
                onClearEvent()
            }
            BackupUiEvent.RestoreDone -> {
                restoreDone = true
                onClearEvent()
            }
            BackupUiEvent.UnlockChanged -> {
                snackbar.showSnackbar(context.getString(R.string.unlock_changed))
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
            ListItem(
                headlineContent = { Text(stringResource(R.string.setting_routing)) },
                supportingContent = { Text(stringResource(routingLabelRes(state.routingStrategy))) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showRoutingDialog = true },
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
            HorizontalDivider()

            SettingsGroupTitle(stringResource(R.string.group_security))
            ListItem(
                headlineContent = { Text(stringResource(R.string.setting_change_unlock)) },
                supportingContent = { Text(stringResource(R.string.setting_change_unlock_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = state.unlockConfigured) { showUnlockDialog = true },
            )
            HorizontalDivider()

            SettingsGroupTitle(stringResource(R.string.group_notifications))
            state.phoneNotify.forEach { (type, enabled) ->
                SwitchRow(
                    title = stringResource(phoneNotifyLabelRes(type)),
                    subtitle = null,
                    checked = enabled,
                    onChecked = { onPhoneNotify(type, it) },
                )
            }
            HorizontalDivider()

            SettingsGroupTitle(stringResource(R.string.group_language_theme))
            ListItem(
                headlineContent = { Text(stringResource(R.string.setting_language)) },
                supportingContent = {
                    Text(AppLocales.OFFERED.firstOrNull { it.first == state.language }?.second ?: "English")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showLanguageDialog = true },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.setting_theme)) },
                supportingContent = { Text(stringResource(themeLabelRes(state.themeMode))) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showThemeDialog = true },
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

    if (showRoutingDialog) {
        RadioDialog(
            title = stringResource(R.string.setting_routing),
            options = ROUTING_OPTIONS.map { stringResource(it.second) },
            selectedIndex = ROUTING_OPTIONS.indexOfFirst { it.first == state.routingStrategy },
            onSelect = { index ->
                showRoutingDialog = false
                onRouting(ROUTING_OPTIONS[index].first)
            },
            onDismiss = { showRoutingDialog = false },
        )
    }

    if (showThemeDialog) {
        RadioDialog(
            title = stringResource(R.string.setting_theme),
            options = THEME_OPTIONS.map { stringResource(it.second) },
            selectedIndex = THEME_OPTIONS.indexOfFirst { it.first == state.themeMode },
            onSelect = { index ->
                showThemeDialog = false
                onTheme(THEME_OPTIONS[index].first)
            },
            onDismiss = { showThemeDialog = false },
        )
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.setting_language)) },
            text = {
                Column {
                    AppLocales.OFFERED.forEach { (tag, name) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showLanguageDialog = false
                                    onLanguage(tag)
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = tag == state.language,
                                onClick = {
                                    showLanguageDialog = false
                                    onLanguage(tag)
                                },
                            )
                            Text(name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showUnlockDialog) {
        ChangeUnlockDialog(
            onDismiss = { showUnlockDialog = false },
            onChange = { old, new ->
                showUnlockDialog = false
                onChangeUnlock(old, new)
            },
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

fun routingLabelRes(strategy: String): Int =
    ROUTING_OPTIONS.firstOrNull { it.first == strategy }?.second ?: R.string.routing_capacity_aware

fun themeLabelRes(mode: String): Int =
    THEME_OPTIONS.firstOrNull { it.first == mode }?.second ?: R.string.theme_system

fun phoneNotifyLabelRes(type: String): Int = when (type) {
    "upload_done" -> R.string.notify_phone_upload_done
    "upload_failed" -> R.string.notify_phone_upload_failed
    "mirror_failed" -> R.string.notify_phone_mirror_failed
    "quota_stale" -> R.string.notify_phone_quota_stale
    "trash_purged" -> R.string.notify_phone_trash_purged
    "token_expired" -> R.string.notify_phone_token_expired
    else -> R.string.app_name
}

/** specs/09 §13 security group: old password verified, then the new one set. */
@Composable
private fun ChangeUnlockDialog(onDismiss: () -> Unit, onChange: (String, String) -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = old.isNotEmpty() && new.length >= 4 && new == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_change_unlock)) },
        text = {
            Column {
                OutlinedTextField(
                    value = old,
                    onValueChange = { old = it },
                    label = { Text(stringResource(R.string.unlock_old_password)) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = new,
                    onValueChange = { new = it },
                    label = { Text(stringResource(R.string.unlock_new_password)) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text(stringResource(R.string.setup_confirm_hint)) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onChange(old, new) }, enabled = valid) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

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
    // The whole row toggles (bigger touch target); the trailing Switch renders
    // the state — its own handler folds into the row's toggleable action.
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        modifier = Modifier
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onChecked,
            )
            .semantics { contentDescription = title },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = null)
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
