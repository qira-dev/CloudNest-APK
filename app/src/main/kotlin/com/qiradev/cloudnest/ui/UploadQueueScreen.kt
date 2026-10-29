package com.qiradev.cloudnest.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qiradev.cloudnest.R
import com.qiradev.cloudnest.data.entity.JobKind
import com.qiradev.cloudnest.data.entity.JobStatus

/**
 * Transfer queue (specs/09 screen 7): per-job name/status/progress with
 * pause/resume/cancel/retry controls, an overall count and the Wi-Fi-only
 * status line (uploads wait for Wi-Fi when the policy is on).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadQueueScreen(
    state: QueueUiState,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String, String) -> Unit,
    onRetry: (String, String) -> Unit,
    onBack: () -> Unit,
    onRetryLoad: () -> Unit,
) {
    LaunchedEffect(Unit) { onRetryLoad() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_queue)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                QueueUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is QueueUiState.Error -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.label_error_generic))
                    TextButton(onClick = onRetryLoad) { Text(stringResource(R.string.action_retry)) }
                }
                is QueueUiState.Ready -> Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = stringResource(
                            R.string.queue_summary,
                            state.rows.count { it.job.status == JobStatus.QUEUED },
                            state.rows.count { it.job.status == JobStatus.RUNNING },
                        ) + if (state.wifiOnly) {
                            " · " + stringResource(R.string.queue_wifi_only_on)
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (state.rows.isEmpty()) {
                        Text(
                            stringResource(R.string.queue_empty),
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 32.dp),
                        )
                    } else {
                        LazyColumn {
                            items(state.rows, key = { it.job.id }) { row ->
                                QueueRowItem(
                                    row = row,
                                    onPause = onPause,
                                    onResume = onResume,
                                    onCancel = onCancel,
                                    onRetry = onRetry,
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRowItem(
    row: QueueRow,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String, String) -> Unit,
    onRetry: (String, String) -> Unit,
) {
    val kindLabel = stringResource(queueKindRes(row.job.kind))
    val statusLabel = stringResource(queueStatusRes(row.job.status))
    ListItem(
        headlineContent = { Text(row.fileName.ifEmpty { kindLabel }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text("$kindLabel · $statusLabel" + (row.job.errorCode?.let { " · $it" } ?: ""))
                row.progressPercent?.let { percent ->
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        },
        trailingContent = {
            Row {
                when (row.job.status) {
                    JobStatus.QUEUED, JobStatus.RUNNING -> {
                        TextButton(onClick = { onPause(row.job.id) }) {
                            Text(stringResource(R.string.queue_pause))
                        }
                        TextButton(onClick = { onCancel(row.job.id, row.job.kind) }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                    JobStatus.PAUSED -> {
                        TextButton(onClick = { onResume(row.job.id) }) {
                            Text(stringResource(R.string.queue_resume))
                        }
                        TextButton(onClick = { onCancel(row.job.id, row.job.kind) }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                    JobStatus.FAILED -> {
                        if (row.job.kind == JobKind.UPLOAD || row.job.kind == JobKind.MIRROR) {
                            Button(onClick = { onRetry(row.job.id, row.job.kind) }) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    }
                    else -> {}
                }
            }
        },
    )
}

fun queueKindRes(kind: String): Int = when (kind) {
    JobKind.UPLOAD -> R.string.job_kind_upload
    JobKind.DOWNLOAD -> R.string.job_kind_download
    JobKind.MIRROR -> R.string.job_kind_mirror
    else -> R.string.job_kind_purge
}

fun queueStatusRes(status: String): Int = when (status) {
    JobStatus.QUEUED -> R.string.job_status_queued
    JobStatus.RUNNING -> R.string.job_status_running
    JobStatus.PAUSED -> R.string.job_status_paused
    JobStatus.DONE -> R.string.job_status_done
    JobStatus.FAILED -> R.string.job_status_failed
    else -> R.string.job_status_cancelled
}
