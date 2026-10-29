package com.qiradev.cloudnest.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qiradev.cloudnest.data.entity.TransferJobEntity
import com.qiradev.cloudnest.engine.diagnostics.AccountDiagnostics
import com.qiradev.cloudnest.engine.diagnostics.ConnectionTestResult
import com.qiradev.cloudnest.engine.diagnostics.DiagnosticsService
import com.qiradev.cloudnest.data.dao.FailureRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Diagnostics screen state (specs/06 §3). */
sealed interface DiagnosticsUiState {
    data object Loading : DiagnosticsUiState
    data class Error(val message: String) : DiagnosticsUiState
    data class Ready(
        val accounts: List<AccountDiagnostics>,
        val jobs: List<TransferJobEntity>,
        val failures: List<FailureRow>,
    ) : DiagnosticsUiState
}

/**
 * Drives the diagnostics screen (specs/06 §3): health/quota/reservations per
 * account, the transfer-job list with the stuck-job sweep, recent failures and
 * the per-account "test connection" probe. Like [ArchiveUiController] this is
 * a pure state holder — no Android imports.
 */
class DiagnosticsUiController(
    private val service: DiagnosticsService,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    var state: DiagnosticsUiState by mutableStateOf(DiagnosticsUiState.Loading)
        private set

    /** Sweep in flight — the button disables itself; a live drain may make it wait. */
    var sweeping: Boolean by mutableStateOf(false)
        private set

    var sweepDone: Boolean by mutableStateOf(false)
        private set

    /** Per-account test-connection results (accountId → result). */
    val testResults = mutableMapOf<String, ConnectionTestResult>()

    /** Accounts with a probe in flight. */
    val testing = mutableSetOf<String>()

    private fun ready() = state as? DiagnosticsUiState.Ready

    fun refresh() {
        scope.launch {
            state = try {
                DiagnosticsUiState.Ready(
                    accounts = service.accountSummaries(),
                    jobs = service.jobs(),
                    failures = service.recentFailures(),
                )
            } catch (e: Exception) {
                DiagnosticsUiState.Error(e.message ?: "unknown")
            }
        }
    }

    fun sweepStuckJobs() {
        if (sweeping) return
        sweeping = true
        sweepDone = false
        scope.launch {
            try {
                service.sweepStuckJobs()
                sweepDone = true
                refresh()
            } catch (e: Exception) {
                state = DiagnosticsUiState.Error(e.message ?: "unknown")
            } finally {
                sweeping = false
            }
        }
    }

    fun testConnection(accountId: String) {
        if (accountId in testing) return
        testing.add(accountId)
        scope.launch {
            try {
                val result = service.testConnection(accountId)
                testResults[accountId] = result
                refresh()
            } finally {
                testing.remove(accountId)
            }
        }
    }

    /** Newer probe result for an account, or null when never tested here. */
    fun resultFor(accountId: String): ConnectionTestResult? = testResults[accountId]
}
