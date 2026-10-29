package com.qiradev.cloudnest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qiradev.cloudnest.R
import com.qiradev.cloudnest.data.entity.ProviderAccountStatus
import com.qiradev.cloudnest.data.entity.QuotaStatus
import com.qiradev.cloudnest.engine.diagnostics.AccountDiagnostics
import com.qiradev.cloudnest.engine.diagnostics.ConnectionTestResult
import com.qiradev.cloudnest.engine.health.Health

/**
 * Accounts screen (specs/09 screen 9): every connected account with its health
 * color, quota bar (stale = yellow signal), label editing, disconnect warning,
 * quota refresh and the path into the connect wizard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    state: AccountsUiState,
    testing: Set<String>,
    resultFor: (String) -> ConnectionTestResult?,
    onRefresh: () -> Unit,
    onConnect: () -> Unit,
    onRename: (String, String) -> Unit,
    onDisconnect: (String) -> Unit,
    onRefreshQuota: (String) -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDisconnectId by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_accounts)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = onConnect) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_connect_account))
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                AccountsUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is AccountsUiState.Error -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.label_error_generic))
                    TextButton(onClick = onRefresh) { Text(stringResource(R.string.action_retry)) }
                }
                is AccountsUiState.Ready -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.accounts, key = { it.account.id }) { account ->
                        AccountRow(
                            account = account,
                            testing = account.account.id in testing,
                            result = resultFor(account.account.id),
                            onRename = { renamingId = account.account.id },
                            onDisconnect = { confirmDisconnectId = account.account.id },
                            onRefreshQuota = { onRefreshQuota(account.account.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    renamingId?.let { id ->
        val current = (state as? AccountsUiState.Ready)?.accounts
            ?.firstOrNull { it.account.id == id }?.account
        RenameDialog(
            initial = current?.label.orEmpty(),
            onDismiss = { renamingId = null },
            onSave = { label ->
                renamingId = null
                onRename(id, label)
            },
        )
    }

    confirmDisconnectId?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDisconnectId = null },
            title = { Text(stringResource(R.string.dialog_disconnect_title)) },
            text = { Text(stringResource(R.string.dialog_disconnect_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisconnectId = null
                        onDisconnect(id)
                    },
                ) { Text(stringResource(R.string.action_disconnect)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnectId = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun AccountRow(
    account: AccountDiagnostics,
    testing: Boolean,
    result: ConnectionTestResult?,
    onRename: () -> Unit,
    onDisconnect: () -> Unit,
    onRefreshQuota: () -> Unit,
) {
    val verdictLabel = stringResource(account.verdict.labelRes())
    val displayName = account.account.label.ifEmpty {
        stringResource(providerNameRes(account.account.provider))
    }
    ListItem(
        headlineContent = {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(10.dp)
                        .background(account.verdict.color(), CircleShape)
                        .semantics { contentDescription = verdictLabel },
                )
                Text(displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        supportingContent = {
            Column {
                QuotaBar(account)
                when (account.account.status) {
                    ProviderAccountStatus.ERROR ->
                        Text(
                            stringResource(R.string.label_needs_login),
                            color = MaterialTheme.colorScheme.error,
                        )
                    ProviderAccountStatus.DISCONNECTED ->
                        Text(
                            stringResource(R.string.label_needs_login),
                            color = MaterialTheme.colorScheme.error,
                        )
                    else -> {}
                }
                when {
                    testing -> Text(stringResource(R.string.test_running))
                    result != null && result.ok -> Text(stringResource(R.string.test_ok))
                    result != null -> Text(
                        stringResource(R.string.test_failed, result.errorCode ?: "?"),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        trailingContent = {
            androidx.compose.foundation.layout.Row {
                IconButton(onClick = onRefreshQuota, enabled = !testing) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cd_refresh_quota))
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.cd_rename_account))
                }
                IconButton(onClick = onDisconnect) {
                    Icon(Icons.Filled.DeleteForever, contentDescription = stringResource(R.string.action_disconnect))
                }
            }
        },
    )
}

@Composable
private fun QuotaBar(account: AccountDiagnostics) {
    val a = account.account
    when {
        a.quotaUnlimited == 1 -> Text(stringResource(R.string.label_quota_unlimited))
        a.quotaTotalBytes == null || a.quotaUsedBytes == null -> {
            Text(stringResource(R.string.label_quota_unknown))
        }
        else -> {
            val fraction = if (a.quotaTotalBytes > 0) {
                (a.quotaUsedBytes.toDouble() / a.quotaTotalBytes).coerceIn(0.0, 1.0)
            } else 0f
            Column {
                Text(
                    stringResource(
                        R.string.label_quota_usage,
                        formatBytes(a.quotaUsedBytes),
                        formatBytes(a.quotaTotalBytes),
                    ),
                )
                // Stale quota reads are a yellow signal (specs/09 screen 9).
                LinearProgressIndicator(
                    progress = { fraction.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    color = if (a.quotaStatus == QuotaStatus.STALE) {
                        Health.DEGRADED.color()
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var label by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_rename_title)) },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.dialog_rename_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(label) }, enabled = label.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Provider display name resource (label fallback, specs/09 screen 9). */
fun providerNameRes(provider: String): Int = when (provider) {
    "google_drive" -> R.string.provider_google_drive
    "dropbox" -> R.string.provider_dropbox
    "onedrive" -> R.string.provider_onedrive
    "pcloud" -> R.string.provider_pcloud
    "mega" -> R.string.provider_mega
    else -> R.string.app_name
}
