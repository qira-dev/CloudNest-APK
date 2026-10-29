package com.qiradev.cloudnest.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qiradev.cloudnest.engine.diagnostics.AccountDiagnostics
import com.qiradev.cloudnest.engine.diagnostics.ConnectionTestResult
import com.qiradev.cloudnest.engine.diagnostics.DiagnosticsService
import com.qiradev.cloudnest.engine.providers.AccountConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Accounts screen state (specs/09 screen 9). */
sealed interface AccountsUiState {
    data object Loading : AccountsUiState
    data class Error(val message: String) : AccountsUiState
    data class Ready(val accounts: List<AccountDiagnostics>) : AccountsUiState
}

/**
 * Drives the accounts screen (specs/09 screen 9): connect, rename, re-login,
 * disconnect (warned), quota refresh, health color + quota bars per account.
 * Pure state holder like the other controllers.
 */
class AccountsUiController(
    private val db: com.qiradev.cloudnest.data.CloudNestDatabase,
    private val connections: AccountConnectionManager,
    private val diagnostics: DiagnosticsService,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    var state: AccountsUiState by mutableStateOf(AccountsUiState.Loading)
        private set

    /** Probe results + in-flight markers, keyed by account id. */
    val testResults = mutableMapOf<String, ConnectionTestResult>()
    val testing = mutableSetOf<String>()

    fun refresh() {
        scope.launch {
            state = try {
                AccountsUiState.Ready(diagnostics.accountSummaries())
            } catch (e: Exception) {
                AccountsUiState.Error(e.message ?: "unknown")
            }
        }
    }

    fun rename(accountId: String, label: String) {
        if (label.isBlank()) return
        scope.launch {
            runCatching { connections.renameAccount(accountId, label) }
            refresh()
        }
    }

    fun disconnect(accountId: String) {
        scope.launch {
            runCatching { connections.disconnect(accountId) }
            refresh()
        }
    }

    fun refreshQuota(accountId: String) {
        if (accountId in testing) return
        testing.add(accountId)
        scope.launch {
            try {
                testResults[accountId] = diagnostics.testConnection(accountId)
                refresh()
            } finally {
                testing.remove(accountId)
            }
        }
    }

    fun resultFor(accountId: String): ConnectionTestResult? = testResults[accountId]
}
