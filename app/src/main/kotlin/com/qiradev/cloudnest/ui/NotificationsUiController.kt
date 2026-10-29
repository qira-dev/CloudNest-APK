package com.qiradev.cloudnest.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.entity.NotificationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Notification feed state (specs/09 screen 12). */
sealed interface NotificationsUiState {
    data object Loading : NotificationsUiState
    data class Error(val message: String) : NotificationsUiState
    data class Ready(val rows: List<NotificationEntity>) : NotificationsUiState
}

/**
 * Drives the notification feed (specs/01 §1.12, specs/09 screen 12): latest
 * rows, unread marker, tap-to-read. The OS mirror of these rows follows the
 * per-type phone policy (specs/05 §2.2 + specs/09 §13) — the feed itself
 * always keeps everything.
 */
class NotificationsUiController(
    private val db: CloudNestDatabase,
    private val scope: CoroutineScope,
) {

    var state: NotificationsUiState by mutableStateOf(NotificationsUiState.Loading)
        private set

    fun refresh() {
        scope.launch {
            state = try {
                NotificationsUiState.Ready(db.notificationDao().latest(FEED_LIMIT))
            } catch (e: Exception) {
                NotificationsUiState.Error(e.message ?: "unknown")
            }
        }
    }

    fun markRead(id: String) {
        scope.launch {
            runCatching { db.notificationDao().markRead(id) }
            refresh()
        }
    }

    companion object {
        /** specs/01 §1.12: the ledger keeps the last 100 — the feed shows them all. */
        const val FEED_LIMIT = 100
    }
}
