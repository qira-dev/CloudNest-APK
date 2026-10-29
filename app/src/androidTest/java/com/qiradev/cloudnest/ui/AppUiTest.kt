package com.qiradev.cloudnest.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qiradev.cloudnest.MainActivity
import com.qiradev.cloudnest.R
import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.DatabaseProvider
import com.qiradev.cloudnest.data.entity.NotificationEntity
import com.qiradev.cloudnest.data.entity.NotificationType
import com.qiradev.cloudnest.engine.security.UnlockManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * specs/09 test + specs/10 UiTest — the main work of each screen, on the
 * emulator: the setup/unlock gate, the settings writes, the notification
 * feed, the transfer queue and the connect wizard's honest not-configured
 * state. Every test starts from a fresh database with a known unlock password
 * so the app opens on the unlock gate (a real first-run gate can't be skipped).
 */
@RunWith(AndroidJUnit4::class)
class AppUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val unlockPassword = "test-pass-1"
    private lateinit var db: CloudNestDatabase

    @Before
    fun freshDatabaseAndUnlock(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        DatabaseProvider.closeAndReset()
        val dir = context.noBackupFilesDir
        listOf(
            CloudNestDatabase.DATABASE_NAME,
            "${CloudNestDatabase.DATABASE_NAME}-wal",
            "${CloudNestDatabase.DATABASE_NAME}-shm",
        ).forEach { name -> File(dir, name).delete() }
        db = DatabaseProvider.get(context)
        UnlockManager(db).setUnlockPassword(unlockPassword)
    }

    private fun str(res: Int, vararg args: Any?): String =
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(res, *args)

    private var scenario: ActivityScenario<MainActivity>? = null

    private fun launchApp(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }

    private fun unlockThroughUi() {
        waitUntilShown(str(R.string.unlock_title))
        typeIntoPassword(unlockPassword)
        compose.onNodeWithText(str(R.string.unlock_action)).performClick()
        waitUntilGone(str(R.string.unlock_title))
    }

    /** The password field keeps its previous text after a wrong attempt — clear it first. */
    private fun typeIntoPassword(text: String) {
        compose.onNodeWithText(str(R.string.setup_password_hint))
            .performTextClearance()
        compose.onNodeWithText(str(R.string.setup_password_hint)).performTextInput(text)
    }

    private fun waitUntilGone(text: String) {
        compose.waitUntil(20_000) {
            !rootReady() || compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitUntilShown(text: String) {
        compose.waitUntil(20_000) {
            rootReady() && compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * The compose root registers asynchronously on slow emulators — queries
     * during that window throw "no compose hierarchies", so waits treat it as
     * "not yet" instead of failing the test.
     */
    private fun rootReady(): Boolean = runCatching {
        compose.onRoot().fetchSemanticsNode()
        true
    }.getOrDefault(false)

    @After
    fun closeApp() {
        scenario?.close()
        scenario = null
    }

    @Test
    fun setupIsOfferedWhenNoUnlockExists(): Unit = runBlocking {
        // No unlock hash at all → the gate offers setup, not unlock.
        db.settingsDao().delete("unlock_hash")
        launchApp()
        waitUntilShown(str(R.string.setup_title))
    }

    @Test
    fun unlockGateBlocksWrongPasswordAndLetsTheRightOneThrough(): Unit = runBlocking {
        val scenario = launchApp()
        unlockThroughUi()
        waitUntilShown(str(R.string.screen_archive))

        // Every (re)creation asks again (specs/08 §2) — recreate avoids a
        // launch race between the dying and the starting activity.
        scenario.recreate()
        waitUntilShown(str(R.string.unlock_title))
        typeIntoPassword("wrong-pass")
        compose.onNodeWithText(str(R.string.unlock_action)).performClick()
        waitUntilShown(str(R.string.unlock_wrong_password))
        typeIntoPassword(unlockPassword)
        compose.onNodeWithText(str(R.string.unlock_action)).performClick()
        waitUntilShown(str(R.string.screen_archive))
    }

    @Test
    fun settingsToggleWritesTheSettingsRow(): Unit = runBlocking {
        launchApp()
        unlockThroughUi()
        waitUntilShown(str(R.string.screen_archive))

        compose.onNodeWithContentDescription(str(R.string.cd_open_settings)).performClick()
        waitUntilShown(str(R.string.group_upload))
        compose.onNodeWithText(str(R.string.setting_wifi_only)).performClick()
        compose.waitUntil(10_000) {
            runBlocking { db.settingsDao().getValue("wifi_only_upload") == "false" }
        }
    }

    @Test
    fun notificationFeedShowsEvents(): Unit = runBlocking {
        db.notificationDao().insert(
            NotificationEntity(
                id = UUID.randomUUID().toString(),
                type = NotificationType.UPLOAD_DONE,
                payload = """{"file":"a.mp4"}""",
                read = 0,
                createdAt = System.currentTimeMillis(),
            ),
        )
        launchApp()
        unlockThroughUi()
        waitUntilShown(str(R.string.screen_archive))

        compose.onNodeWithContentDescription(str(R.string.cd_open_notifications)).performClick()
        waitUntilShown(str(R.string.notif_upload_done, "a.mp4"))
    }

    @Test
    fun queueScreenListsQueuedUploads(): Unit = runBlocking {
        seedFileWithJob()

        launchApp()
        unlockThroughUi()
        waitUntilShown(str(R.string.screen_archive))

        compose.onNodeWithContentDescription(str(R.string.cd_open_queue)).performClick()
        waitUntilShown(str(R.string.screen_queue))
        waitUntilShown("queue-file.mp4")
        // Row controls are the queue's main work (specs/09 screen 7); the
        // Wi-Fi-only summary flag is covered at the controller level.
        waitUntilShown(str(R.string.queue_pause))
    }

    /** Minimal queue row: one folder + one file + one queued upload job. */
    private suspend fun seedFileWithJob() {
        db.providerAccountDao().insert(
            com.qiradev.cloudnest.data.entity.ProviderAccountEntity(
                id = "acc-1",
                provider = "google_drive",
                label = "Test drive",
                status = "connected",
                encryptedTokens = null,
                tokenExpiresAt = 0,
                quotaTotalBytes = 100,
                quotaUsedBytes = 10,
                quotaUnlimited = 0,
                quotaStatus = "current",
                quotaObservedAt = 1,
                quotaScopeKey = null,
                rootPath = "root",
                createdAt = 1,
                lastUsedAt = 1,
            ),
        )
        db.folderDao().insert(
            com.qiradev.cloudnest.data.entity.FolderEntity(
                id = "folder-1", parentId = null, name = "docs",
                createdAt = 1, deletedAt = null, purgeAt = null,
            ),
        )
        db.fileDao().insert(
            com.qiradev.cloudnest.data.entity.FileEntity(
                id = "file-1", folderId = "folder-1", name = "queue-file.mp4",
                sizeBytes = 1000, mimeType = "video/mp4", chunkSizeMib = 64,
                chunkCount = 1, sha256 = "h", status = "uploading",
                createdAt = 1, updatedAt = 1, deletedAt = null, purgeAt = null,
            ),
        )
        db.transferJobDao().insert(
            com.qiradev.cloudnest.data.entity.TransferJobEntity(
                id = "job-1", kind = "upload", fileId = "file-1",
                status = "queued", errorCode = null, attempts = 0,
                createdAt = 1, updatedAt = 1,
            ),
        )
    }

    @Test
    fun connectWizardFailsHonestlyWithoutOAuthConfig(): Unit = runBlocking {
        launchApp()
        unlockThroughUi()
        waitUntilShown(str(R.string.screen_archive))

        compose.onNodeWithContentDescription(str(R.string.cd_open_accounts)).performClick()
        waitUntilShown(str(R.string.screen_accounts))
        compose.onNodeWithContentDescription(str(R.string.cd_connect_account)).performClick()
        waitUntilShown(str(R.string.screen_connect))

        compose.onNodeWithText(str(R.string.provider_google_drive)).performClick()
        // clientId is empty until the owner registers the OAuth client — the
        // wizard must say so instead of pretending (handoff: Not Run label).
        waitUntilShown(str(R.string.connect_not_configured))
    }
}
