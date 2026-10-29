package com.qiradev.cloudnest.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qiradev.cloudnest.R
import com.qiradev.cloudnest.data.entity.NotificationEntity
import com.qiradev.cloudnest.data.entity.NotificationType
import java.text.DateFormat
import java.util.Date
import org.json.JSONObject

/**
 * Notification feed (specs/09 screen 12): the in-app event ledger — every
 * transfer/mirror/quota/trash/token event lands here; the ones the user allows
 * also reach the phone shade (specs/05 §2.2). Tap marks the row read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    state: NotificationsUiState,
    onBack: () -> Unit,
    onMarkRead: (String) -> Unit,
    onRetry: () -> Unit,
) {
    LaunchedEffect(Unit) { onRetry() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_notifications)) },
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
                NotificationsUiState.Loading -> Text(
                    stringResource(R.string.label_loading),
                    modifier = Modifier.align(Alignment.Center),
                )
                is NotificationsUiState.Error -> Column2(
                    onRetry = onRetry,
                    modifier = Modifier.align(Alignment.Center),
                )
                is NotificationsUiState.Ready -> {
                    if (state.rows.isEmpty()) {
                        Text(
                            stringResource(R.string.label_notifications_empty),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        LazyColumn {
                            items(state.rows, key = { it.id }) { row ->
                                NotificationRow(row) { onMarkRead(row.id) }
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
private fun Column2(onRetry: () -> Unit, modifier: Modifier) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.label_error_generic))
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

/** Human text for one feed row: type title + the payload's file/provider detail. */
@Composable
private fun feedText(row: NotificationEntity): String {
    val payload = remember(row.payload) { runCatching { JSONObject(row.payload) }.getOrNull() }
    val file = payload?.optString("file")?.takeIf { it.isNotBlank() && it != "null" }.orEmpty()
    val provider = payload?.optString("provider")
        ?.takeIf { it.isNotBlank() && it != "null" }
        ?.let { stringResource(providerNameRes(it)) }
        .orEmpty()
    return when (row.type) {
        NotificationType.UPLOAD_DONE -> stringResource(R.string.notif_upload_done, file)
        NotificationType.UPLOAD_FAILED ->
            stringResource(
                R.string.notif_upload_failed,
                listOfNotNull(file.ifEmpty { null }, payload?.optString("code")).joinToString(" — "),
            )
        NotificationType.MIRROR_DONE -> stringResource(R.string.notif_mirror_done, file)
        NotificationType.MIRROR_FAILED -> stringResource(R.string.notif_mirror_failed, file)
        NotificationType.QUOTA_STALE -> stringResource(R.string.notif_quota_stale, provider)
        NotificationType.TRASH_PURGED -> stringResource(R.string.notif_trash_purged)
        NotificationType.TOKEN_EXPIRED -> stringResource(R.string.notif_token_expired, provider)
        else -> stringResource(R.string.label_error_generic)
    }
}

@Composable
private fun NotificationRow(row: NotificationEntity, onRead: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                feedText(row),
                fontWeight = if (row.read == 0) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(row.createdAt)),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            if (row.read == 0) {
                Text(
                    stringResource(R.string.label_unread),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        modifier = Modifier.clickable(onClick = onRead),
    )
}
