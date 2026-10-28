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
 * with their accounts, the protection badge, and the mirror action. Download
 * and share buttons arrive with the FileProvider export binding (step 9).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailScreen(
    state: FileDetailUiState,
    onBack: () -> Unit,
    onCompleteMirror: () -> Unit,
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
                            if (state.badge != ProtectionBadge.PROTECTED) {
                                Button(
                                    onClick = onCompleteMirror,
                                    modifier = Modifier.fillMaxWidth(),
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
