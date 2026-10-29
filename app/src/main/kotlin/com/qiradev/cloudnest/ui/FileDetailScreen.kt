package com.qiradev.cloudnest.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qiradev.cloudnest.R
import com.qiradev.cloudnest.ui.ProtectionBadge
import java.text.DateFormat
import java.util.Date

/**
 * File detail (specs/09 screen 5, specs/06 §1.3): metadata, per-chunk copies
 * with their accounts, the protection badge, the mirror action and — from
 * step 9 — the download/export actions (specs/03 §4: open/share through the
 * cache export, save through SAF).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailScreen(
    state: FileDetailUiState,
    downloadState: DownloadUiState?,
    onBack: () -> Unit,
    onCompleteMirror: () -> Unit,
    onDownloadOpenShare: () -> Unit,
    onDownloadSaveTo: (android.net.Uri) -> Unit,
    onMoveToTrash: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = (state as? FileDetailUiState.Ready)?.file?.name
                            ?: stringResource(R.string.screen_file_detail),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                is FileDetailUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
                is FileDetailUiState.Error -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.label_error_generic))
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                }
                is FileDetailUiState.Ready -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                state.file.name,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${formatBytes(state.file.sizeBytes)} · ${state.file.mimeType}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.label_chunk_count, state.copies.size),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                DateFormat.getDateInstance(DateFormat.MEDIUM)
                                    .format(Date(state.file.createdAt)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val badgeText = when (state.badge) {
                                ProtectionBadge.PROTECTED -> stringResource(R.string.badge_protected)
                                ProtectionBadge.MIRROR_PENDING -> stringResource(R.string.badge_mirror_pending)
                                ProtectionBadge.MIRROR_FAILED -> stringResource(R.string.badge_mirror_failed)
                            }
                            AssistChip(
                                onClick = {},
                                label = { Text(badgeText) },
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        HorizontalDivider()
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(state.copies, key = { "chunk-${it.chunkIndex}" }) { copy ->
                                ListItem(
                                    headlineContent = {
                                        Text(stringResource(R.string.label_chunk_title, copy.chunkIndex))
                                    },
                                    leadingContent = {
                                        Icon(Icons.Filled.Description, contentDescription = null)
                                    },
                                    supportingContent = {
                                        Column {
                                            if (copy.rows.isEmpty()) {
                                                Text(stringResource(R.string.label_no_copies))
                                            }
                                            copy.rows.forEach { placement ->
                                                Text(
                                                    stringResource(
                                                        if (placement.isMirror) {
                                                            R.string.label_mirror_copy
                                                        } else {
                                                            R.string.label_primary_copy
                                                        },
                                                        placement.accountLabel,
                                                    ) + if (placement.verified) {
                                                        " · " + stringResource(R.string.label_verified)
                                                    } else {
                                                        ""
                                                    },
                                                )
                                            }
                                        }
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            DownloadActionsRow(
                                state = state,
                                downloadState = downloadState,
                                onDownloadOpenShare = onDownloadOpenShare,
                                onDownloadSaveTo = onDownloadSaveTo,
                            )
                            if (state.badge != ProtectionBadge.PROTECTED) {
                                Button(
                                    onClick = onCompleteMirror,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                ) { Text(stringResource(R.string.action_complete_mirror)) }
                            }
                            OutlinedButton(
                                onClick = onMoveToTrash,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            ) { Text(stringResource(R.string.action_move_to_trash)) }
                        }
                    }
                }
            }
        }
    }
}

/** The two download paths of specs/03 §4 + the running download's progress. */
@Composable
private fun DownloadActionsRow(
    state: FileDetailUiState.Ready,
    downloadState: DownloadUiState?,
    onDownloadOpenShare: () -> Unit,
    onDownloadSaveTo: (android.net.Uri) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val saveTarget = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> if (uri != null) onDownloadSaveTo(uri) }

    androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onDownloadOpenShare,
            enabled = downloadState == null,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.action_download_open)) }
        OutlinedButton(
            onClick = { saveTarget.launch(state.file.name) },
            enabled = downloadState == null,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        ) { Text(stringResource(R.string.action_download_save)) }
    }
    when (downloadState) {
        is DownloadUiState.Running -> androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.download_running), style = MaterialTheme.typography.bodySmall)
        }
        is DownloadUiState.Done -> downloadState.filePath?.let { path ->
            // specs/03 §4.1: the assembled cache export is opened/shared through FileProvider.
            TextButton(
                onClick = { shareExport(context, path, state.file.mimeType) },
                modifier = Modifier.padding(top = 4.dp),
            ) { Text(stringResource(R.string.action_share_export)) }
        }
        is DownloadUiState.Failed -> Text(
            stringResource(R.string.download_failed, downloadState.errorCode),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
        null -> {}
    }
}

/** Share intent for a finished cache export (specs/03 §4.1). */
private fun shareExport(context: android.content.Context, filePath: String, mimeType: String) {
    val file = java.io.File(filePath)
    if (!file.exists()) return
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.files",
        file,
    )
    val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mimeType.ifEmpty { "application/octet-stream" }
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        android.content.Intent.createChooser(share, file.name)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
