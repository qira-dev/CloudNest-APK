package com.qiradev.cloudnest.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.qiradev.cloudnest.AppServices
import com.qiradev.cloudnest.engine.security.UnlockManager
import kotlinx.coroutines.launch

/** First-run gate (specs/08 §2, specs/09 screens 1–2). */
private sealed interface GateState {
    data object Loading : GateState
    data object NeedsSetup : GateState
    data object NeedsUnlock : GateState
    data object Ready : GateState
}

private sealed interface AppScreen {
    data object Archive : AppScreen
    data class Detail(val fileId: String) : AppScreen
    data object Trash : AppScreen
    data object Settings : AppScreen
    data object Diagnostics : AppScreen
    data object Accounts : AppScreen
    data object Connect : AppScreen
    data object Notifications : AppScreen
    data object Queue : AppScreen
}

/**
 * App shell (specs/09): the unlock gate, then state-based navigation across
 * archive, file detail, trash, settings, diagnostics, accounts, connect
 * wizard, notification feed and the transfer queue. The engine graph is
 * assembled once per composition root through [AppServices] and shared by
 * every screen and every background worker.
 */
@Composable
fun CloudNestApp(oauthCallback: CloudNestOAuth? = null, onAppContentVisible: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppServices.db(context) }
    val unlock = remember { UnlockManager(db) }

    var gate by remember { mutableStateOf<GateState>(GateState.Loading) }
    var themeMode by remember { mutableStateOf("system") }

    LaunchedEffect(Unit) {
        gate = if (unlock.isConfigured()) GateState.NeedsUnlock else GateState.NeedsSetup
        themeMode = loadThemeMode(db)
        // Unlock gates never carry ads (specs/07 §3) — the app-open fires once
        // the content is actually visible behind the gate.
        if (gate == GateState.Ready) onAppContentVisible()
    }

    // OAuth redirect (specs/04 §2) — complete the connection behind the gate.
    LaunchedEffect(gate) {
        if (gate == GateState.Ready) onAppContentVisible()
    }

    LaunchedEffect(oauthCallback) {
        val callback = oauthCallback ?: return@LaunchedEffect
        runCatching {
            AppServices.connectionManager(db).completeConnection(callback.code, callback.state)
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (gate) {
            GateState.Loading -> CircularProgressIndicator()
            GateState.NeedsSetup -> {
                val settingsController = remember { SettingsUiController(db, scope, unlock = unlock) }
                SetupScreen(
                    onSetup = { password ->
                        scope.launch {
                            unlock.setUnlockPassword(password)
                            gate = GateState.Ready
                        }
                    },
                    onRestore = { uri -> settingsController.restoreBackup(context, uri) },
                    busy = settingsController.busy,
                    event = settingsController.event,
                    onClearEvent = { settingsController.clearEvent() },
                    scope = scope,
                )
            }
            GateState.NeedsUnlock -> UnlockScreen(
                unlock = unlock,
                onUnlocked = { gate = GateState.Ready },
            )
            GateState.Ready -> MainAppScreens(db, scope, themeMode, unlock, onThemeChanged = { themeMode = it })
        }
    }
}

@Composable
private fun MainAppScreens(
    db: com.qiradev.cloudnest.data.CloudNestDatabase,
    scope: kotlinx.coroutines.CoroutineScope,
    themeMode: String,
    unlock: UnlockManager,
    onThemeChanged: (String) -> Unit,
) {
    val context = LocalContext.current
    val archiveService = remember { AppServices.archiveService(db) }
    val mirrorService = remember { AppServices.mirrorService(db) }
    val controller = remember {
        ArchiveUiController(db, archiveService, mirrorService, scope, AppServices.health())
    }
    val diagnosticsController = remember { DiagnosticsUiController(AppServices.diagnosticsService(db), scope) }
    val settingsController = remember { SettingsUiController(db, scope, unlock = unlock) }
    val accountsController = remember {
        AccountsUiController(db, AppServices.connectionManager(db), AppServices.diagnosticsService(db), scope)
    }
    val notificationsController = remember { NotificationsUiController(db, scope) }
    val queueController = remember {
        // Queue controls are queue-row operations — settings-independent services.
        QueueUiController(db, AppServices.uploadService(db), AppServices.mirrorService(db), AppServices.downloadService(db), scope)
    }
    val downloadController = remember { DownloadUiController(context, db, AppServices.downloadService(db), scope) }

    var screen by remember { mutableStateOf<AppScreen>(AppScreen.Archive) }
    var unreadCount by remember { mutableStateOf(0) }

    // Unread badge for the bell (specs/09 screen 12).
    LaunchedEffect(Unit) {
        while (true) {
            unreadCount = runCatching { db.notificationDao().listUnread().size }.getOrDefault(0)
            kotlinx.coroutines.delay(3000)
        }
    }

    when (val current = screen) {
        AppScreen.Archive -> {
            BackHandler(enabled = (controller.folderState as? FolderUiState.Ready)
                ?.breadcrumbs?.any { it.name.isNotEmpty() } == true) {
                controller.openFolder(null)
            }
            ArchiveScreen(
                state = controller.folderState,
                accountHealth = controller.accountHealth,
                unreadCount = unreadCount,
                onOpenFolder = { id -> controller.openFolder(id) },
                onOpenRoot = { controller.openFolder(null) },
                onOpenTrash = {
                    screen = AppScreen.Trash
                    controller.refreshTrash()
                },
                onOpenSettings = {
                    screen = AppScreen.Settings
                    settingsController.load()
                },
                onOpenAccounts = { screen = AppScreen.Accounts },
                onOpenNotifications = { screen = AppScreen.Notifications },
                onOpenQueue = { screen = AppScreen.Queue },
                onOpenDiagnostics = { screen = AppScreen.Diagnostics },
                onCreateFolder = { name -> controller.createFolder(name) },
                onTrashFolder = { id -> controller.trashFolder(id) },
                onTrashFile = { id -> controller.trashFile(id) },
                onOpenFile = { id ->
                    screen = AppScreen.Detail(id)
                    controller.openFileDetail(id)
                    downloadController.clear()
                },
                onRetry = { controller.refreshFolder() },
            )
        }
        is AppScreen.Detail -> {
            BackHandler { screen = AppScreen.Archive }
            FileDetailScreen(
                state = controller.fileDetailState,
                downloadState = downloadController.state,
                onBack = { screen = AppScreen.Archive },
                onCompleteMirror = { controller.completeMirror() },
                onDownloadOpenShare = {
                    controller.fileDetailState.let { s ->
                        (s as? FileDetailUiState.Ready)?.file?.id?.let { downloadController.downloadToCache(it) }
                    }
                },
                onDownloadSaveTo = { uri ->
                    controller.fileDetailState.let { s ->
                        (s as? FileDetailUiState.Ready)?.file?.id?.let { downloadController.downloadToSaf(it, uri) }
                    }
                },
                onMoveToTrash = {
                    controller.trashFromDetail()
                    screen = AppScreen.Archive
                },
                onRetry = { controller.refreshFileDetail() },
            )
        }
        AppScreen.Trash -> {
            BackHandler { screen = AppScreen.Archive }
            TrashScreen(
                state = controller.trashState,
                onBack = { screen = AppScreen.Archive },
                onRestoreFile = { id -> controller.restoreFile(id) },
                onRestoreFolder = { id -> controller.restoreFolder(id) },
                onDeleteFileForever = { id -> controller.deleteFileForever(id) },
                onDeleteFolderForever = { id -> controller.deleteFolderForever(id) },
                onEmptyTrash = { controller.emptyTrash() },
                onRetry = { controller.refreshTrash() },
            )
        }
        AppScreen.Settings -> {
            BackHandler { screen = AppScreen.Archive }
            SettingsScreen(
                state = settingsController.state,
                busy = settingsController.busy,
                event = settingsController.event,
                onBack = { screen = AppScreen.Archive },
                onWifiOnly = { settingsController.setWifiOnlyUpload(it) },
                onChunkSize = { settingsController.setChunkSizeMib(it) },
                onRouting = { settingsController.setRoutingStrategy(it) },
                onMirror = { settingsController.setMirrorEnabled(it) },
                onTrashRetention = { settingsController.setTrashRetentionDays(it) },
                onBackupReminder = { settingsController.setBackupReminderEnabled(it) },
                onExport = { uri -> settingsController.exportBackup(context, uri) },
                onImport = { uri -> settingsController.restoreBackup(context, uri) },
                onTheme = { mode ->
                    settingsController.setThemeMode(mode)
                    onThemeChanged(mode)
                },
                onLanguage = { tag ->
                    settingsController.setLanguage(tag) {
                        // Recreate so attachBaseContext picks up the new locale.
                        (context as? android.app.Activity)?.recreate()
                    }
                },
                onPhoneNotify = { type, enabled -> settingsController.setPhoneNotify(type, enabled) },
                onChangeUnlock = { old, new -> settingsController.changeUnlock(old, new) },
                onClearEvent = { settingsController.clearEvent() },
            )
        }
        AppScreen.Diagnostics -> {
            BackHandler { screen = AppScreen.Archive }
            DiagnosticsScreen(
                state = diagnosticsController.state,
                sweeping = diagnosticsController.sweeping,
                sweepDone = diagnosticsController.sweepDone,
                onRefresh = { diagnosticsController.refresh() },
                onSweep = { diagnosticsController.sweepStuckJobs() },
                onTestConnection = { accountId -> diagnosticsController.testConnection(accountId) },
                resultFor = { accountId -> diagnosticsController.resultFor(accountId) },
                isTesting = { accountId -> accountId in diagnosticsController.testing },
                onBack = { screen = AppScreen.Archive },
            )
        }
        AppScreen.Accounts -> {
            BackHandler { screen = AppScreen.Archive }
            AccountsScreen(
                state = accountsController.state,
                testing = accountsController.testing,
                resultFor = { id -> accountsController.resultFor(id) },
                onRefresh = { accountsController.refresh() },
                onConnect = { screen = AppScreen.Connect },
                onRename = { id, label -> accountsController.rename(id, label) },
                onDisconnect = { id -> accountsController.disconnect(id) },
                onRefreshQuota = { id -> accountsController.refreshQuota(id) },
                onBack = { screen = AppScreen.Archive },
            )
        }
        AppScreen.Connect -> {
            BackHandler { screen = AppScreen.Accounts }
            ConnectWizardScreen(
                onComplete = { screen = AppScreen.Accounts },
                onBack = { screen = AppScreen.Accounts },
            )
        }
        AppScreen.Notifications -> {
            BackHandler { screen = AppScreen.Archive }
            NotificationsScreen(
                state = notificationsController.state,
                onBack = { screen = AppScreen.Archive },
                onMarkRead = { id -> notificationsController.markRead(id) },
                onRetry = { notificationsController.refresh() },
            )
        }
        AppScreen.Queue -> {
            BackHandler { screen = AppScreen.Archive }
            UploadQueueScreen(
                state = queueController.state,
                onPause = { id -> queueController.pause(id) },
                onResume = { id -> queueController.resume(id) },
                onCancel = { id, kind -> queueController.cancel(id, kind) },
                onRetry = { id, kind -> queueController.retry(id, kind) },
                onBack = { screen = AppScreen.Archive },
                onRetryLoad = { queueController.refresh() },
            )
        }
    }
}
