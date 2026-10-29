package com.qiradev.cloudnest.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qiradev.cloudnest.R
import com.qiradev.cloudnest.data.entity.JobKind
import com.qiradev.cloudnest.engine.diagnostics.AccountDiagnostics
import com.qiradev.cloudnest.engine.diagnostics.ConnectionTestResult
import com.qiradev.cloudnest.engine.health.Health

/** Health verdict → chip color (specs/06 §2.3: green / yellow / red). */
fun Health.color(): Color = when (this) {
    Health.HEALTHY -> Color(0xFF2E7D32)
    Health.DEGRADED -> Color(0xFFF9A825)
    Health.DOWN -> Color(0xFFC62828)
}

fun Health.labelRes(): Int = when (this) {
    Health.HEALTHY -> R.string.health_healthy
    Health.DEGRADED -> R.string.health_degraded
    Health.DOWN -> R.string.health_down
}

/**
 * Diagnostics screen (specs/06 §3): per-account health + quota + reservations,
 * the transfer-job list with the stuck-job sweep, recent failures and the
 * per-account "test connection" probe. Loading/error/empty states everywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    sweeping: Boolean,
    sweepDone: Boolean,
    onRefresh: () -> Unit,
    onSweep: () -> Unit,
    onTestConnection: (String) -> Unit,
    resultFor: (String) -> ConnectionTestResult?,
    isTesting: (String) -> Boolean,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_diagnostics)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            DiagnosticsUiState.Loading -> Centered { CircularProgressIndicator() }
            is DiagnosticsUiState.Error -> Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.label_error_generic))
                    TextButton(onClick = onRefresh) { Text(stringResource(R.string.action_retry)) }
                }
            }
            is DiagnosticsUiState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(R.string.diagnostics_accounts),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(onClick = onSweep, enabled = !sweeping) {
                            Text(stringResource(R.string.action_sweep_stuck_jobs))
                        }
                    }
                }
                if (sweepDone) {
                    item { Text(
                        stringResource(R.string.sweep_done),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) }
                }
                if (state.accounts.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.diagnostics_empty_accounts),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(state.accounts, key = { it.account.id }) { account ->
                    AccountDiagnosticsRow(
                        account = account,
                        result = resultFor(account.account.id),
                        testing = isTesting(account.account.id),
                        onTestConnection = { onTestConnection(account.account.id) },
                    )
                    HorizontalDivider()
                }
                item {
                    Text(
                        stringResource(R.string.diagnostics_jobs),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (state.jobs.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.diagnostics_empty_jobs),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(state.jobs, key = { "job-${it.id}" }) { job ->
                    JobRow(job)
                    HorizontalDivider()
                }
                item {
                    Text(
                        stringResource(R.string.diagnostics_failures),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (state.failures.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.diagnostics_empty_failures),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(state.failures) { failure ->
                    ListItem(
                        headlineContent = {
                            Text(
                                stringResource(
                                    R.string.failure_row,
                                    failure.fileName,
                                    failure.chunkIndex,
                                    failure.errorCode ?: "?",
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountDiagnosticsRow(
    account: AccountDiagnostics,
    result: ConnectionTestResult?,
    testing: Boolean,
    onTestConnection: () -> Unit,
) {
    val verdictLabel = stringResource(account.verdict.labelRes())
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(10.dp)
                        .background(account.verdict.color(), CircleShape)
                        .semantics { contentDescription = verdictLabel },
                )
                Text(
                    account.account.label.ifEmpty { account.account.provider },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        supportingContent = {
            Column {
                QuotaLine(account)
                Text(stringResource(R.string.label_reservations, formatBytes(account.reservedBytes)))
                account.lastErrorCode?.let { code ->
                    Text(
                        stringResource(R.string.label_last_error, code),
                        color = MaterialTheme.colorScheme.error,
                    )
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
            TextButton(onClick = onTestConnection, enabled = !testing) {
                Text(stringResource(R.string.action_test_connection))
            }
        },
    )
}

@Composable
private fun QuotaLine(account: AccountDiagnostics) {
    val a = account.account
    when {
        a.quotaUnlimited == 1 -> Text(stringResource(R.string.label_quota_unlimited))
        a.quotaTotalBytes == null || a.quotaUsedBytes == null ->
            Text(stringResource(R.string.label_quota_unknown))
        else -> Text(
            stringResource(
                R.string.label_quota_usage,
                formatBytes(a.quotaUsedBytes),
                formatBytes(a.quotaTotalBytes),
            ),
        )
    }
}

@Composable
private fun JobRow(job: com.qiradev.cloudnest.data.entity.TransferJobEntity) {
    val kindLabel = stringResource(
        when (job.kind) {
            JobKind.UPLOAD -> R.string.job_kind_upload
            JobKind.DOWNLOAD -> R.string.job_kind_download
            JobKind.MIRROR -> R.string.job_kind_mirror
            else -> R.string.job_kind_purge
        },
    )
    val statusLabel = stringResource(
        when (job.status) {
            "queued" -> R.string.job_status_queued
            "running" -> R.string.job_status_running
            "paused" -> R.string.job_status_paused
            "done" -> R.string.job_status_done
            "failed" -> R.string.job_status_failed
            else -> R.string.job_status_cancelled
        },
    )
    ListItem(
        headlineContent = { Text("$kindLabel — $statusLabel") },
        supportingContent = { job.errorCode?.let { Text(it) } },
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        content()
    }
}
