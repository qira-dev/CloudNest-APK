package com.qiradev.cloudnest.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.qiradev.cloudnest.AppServices
import kotlinx.coroutines.launch

private sealed interface AppScreen {
    data object Archive : AppScreen
    data object Trash : AppScreen
}

/**
 * App shell (specs/09): state-based navigation between the archive browse screen
 * and the trash screen. The engine graph is assembled once per composition root
 * through [AppServices] and shared by every future screen.
 */
@Composable
fun CloudNestApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppServices.db(context) }
    val archiveService = remember { AppServices.archiveService(db) }
    val controller = remember { ArchiveUiController(db, archiveService, scope) }

    var screen by remember { mutableStateOf<AppScreen>(AppScreen.Archive) }

    LaunchedEffect(Unit) { controller.refreshFolder() }

    when (screen) {
        AppScreen.Archive -> {
            BackHandler(enabled = (controller.folderState as? FolderUiState.Ready)
                ?.breadcrumbs?.any { it.name.isNotEmpty() } == true) {
                controller.openFolder(null)
            }
            ArchiveScreen(
                state = controller.folderState,
                onOpenFolder = { id -> controller.openFolder(id) },
                onOpenRoot = { controller.openFolder(null) },
                onOpenTrash = {
                    screen = AppScreen.Trash
                    controller.refreshTrash()
                },
                onCreateFolder = { name -> controller.createFolder(name) },
                onTrashFolder = { id -> controller.trashFolder(id) },
                onTrashFile = { id -> controller.trashFile(id) },
                onRetry = { controller.refreshFolder() },
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
    }
}
