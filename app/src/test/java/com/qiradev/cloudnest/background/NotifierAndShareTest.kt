package com.qiradev.cloudnest.background

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.qiradev.cloudnest.data.buildTestDb
import com.qiradev.cloudnest.data.entity.NotificationType
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** specs/05 §2 + §6 — phone-notification policy, the feed relay and the durable source store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotifierTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun defaultPolicyShowsFailuresAndQuotaWarningsOnly() {
        // specs/05 §2.2: failures + quota warnings ON; successful uploads OFF.
        assertTrue(Notifier.showsOnPhone(NotificationType.UPLOAD_FAILED))
        assertTrue(Notifier.showsOnPhone(NotificationType.MIRROR_FAILED))
        assertTrue(Notifier.showsOnPhone(NotificationType.QUOTA_STALE))
        assertTrue(Notifier.showsOnPhone(NotificationType.TOKEN_EXPIRED))
        assertFalse(Notifier.showsOnPhone(NotificationType.UPLOAD_DONE))
        assertFalse(Notifier.showsOnPhone(NotificationType.MIRROR_DONE))
        assertFalse(Notifier.showsOnPhone(NotificationType.TRASH_PURGED))
    }

    @Test
    fun channelsAreCreated() {
        val notifier = Notifier(context)
        notifier.ensureChannels()
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(manager.getNotificationChannel(Notifier.CHANNEL_PROGRESS))
        assertNotNull(manager.getNotificationChannel(Notifier.CHANNEL_EVENTS))
    }

    @Test
    fun deniedPermissionPathPostsNothingAndNeverCrashes() {
        val notifier = Notifier(context)
        notifier.ensureChannels()
        // Simulate a user who refused notifications (specs/05 §3.1: the app keeps working).
        Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)

        notifier.postEvent(NotificationType.UPLOAD_FAILED, "Upload failed: a.mp4")
        notifier.postEvent(NotificationType.QUOTA_STALE, "Quota out of date")

        val manager = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
        assertEquals(0, manager.size())
    }

    @Test
    fun grantedPermissionPostsFailureEvents() {
        val notifier = Notifier(context)
        notifier.ensureChannels()
        // API 33+ gates on the runtime permission, not just the manager switch.
        Shadows.shadowOf(context as android.app.Application).grantPermissions(
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)

        notifier.postEvent(NotificationType.UPLOAD_FAILED, "Upload failed: a.mp4")

        val manager = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
        assertEquals(1, manager.size())
    }

    @Test
    fun relayMirrorsNewFeedRowsUnderThePolicy() = runBlocking {
        val db = buildTestDb()
        val notifier = Notifier(context)
        notifier.ensureChannels()
        Shadows.shadowOf(context as android.app.Application).grantPermissions(
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        val manager = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
        val relayStart = System.currentTimeMillis()

        // New rows since relay start: failure shows, success stays feed-only.
        // The same query the relay loop runs picks the rows; relayRow applies
        // the phone policy — synchronous, so CI timing cannot flake it.
        db.notificationDao().insert(
            com.qiradev.cloudnest.data.testNotification(id = "n1", createdAt = System.currentTimeMillis() + 1)
                .copy(type = NotificationType.UPLOAD_FAILED, payload = """{"file":"a.mp4"}"""),
        )
        db.notificationDao().insert(
            com.qiradev.cloudnest.data.testNotification(id = "n2", createdAt = System.currentTimeMillis() + 2)
                .copy(type = NotificationType.UPLOAD_DONE, payload = """{"file":"a.mp4"}"""),
        )
        db.notificationDao().insert(
            com.qiradev.cloudnest.data.testNotification(id = "n3", createdAt = System.currentTimeMillis() + 3)
                .copy(type = NotificationType.MIRROR_DONE, payload = """{"file":"b.mp4"}"""),
        )
        val rows = db.notificationDao().observeNewSince(relayStart).first()
        rows.forEach { NotificationRelay.relayRow(it, notifier, { db }) }

        assertEquals(1, manager.size())
        assertTrue(manager.allNotifications[0].extras.getString(android.app.Notification.EXTRA_TEXT).orEmpty().contains("a.mp4"))
    }

    @Test
    fun failedNoticeCarriesRetryActionAndTokenExpiryNamesTheProvider() = runBlocking {
        val db = buildTestDb()
        val notifier = Notifier(context)
        notifier.ensureChannels()
        Shadows.shadowOf(context as android.app.Application).grantPermissions(
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        val manager = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
        // One base timestamp anchors both inserts and both queries — row
        // created_at is always > the query's `since`, regardless of clock drift.
        val base = System.currentTimeMillis()

        // specs/05 §2.2: a failed upload reaches the phone with file name + Retry.
        db.notificationDao().insert(
            com.qiradev.cloudnest.data.testNotification(id = "f1", createdAt = base + 1)
                .copy(
                    type = NotificationType.UPLOAD_FAILED,
                    payload = """{"file":"a.mp4","code":"no_free_space","job":"job-9"}""",
                ),
        )
        val rows = db.notificationDao().observeNewSince(base).first()
        rows.forEach { NotificationRelay.relayRow(it, notifier, { db }) }
        val failed = manager.allNotifications.single()
        val retryTitle = context.getString(com.qiradev.cloudnest.R.string.action_retry)
        assertNotNull(failed.actions)
        assertTrue(failed.actions.any { it.title == retryTitle })
        assertTrue(failed.extras.getString(android.app.Notification.EXTRA_TEXT)!!.contains("a.mp4"))

        // Token expiry (specs/05 §2.2) reaches the phone and names the provider.
        db.notificationDao().insert(
            com.qiradev.cloudnest.data.testNotification(id = "f2", createdAt = base + 2)
                .copy(type = NotificationType.TOKEN_EXPIRED, payload = """{"provider":"google_drive"}"""),
        )
        val rows2 = db.notificationDao().observeNewSince(base).first()
        rows2.forEach { NotificationRelay.relayRow(it, notifier, { db }) }
        val token = manager.allNotifications.single()
        assertTrue(token.extras.getString(android.app.Notification.EXTRA_TEXT)!!.contains("Google Drive"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareUploadsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun shareUrisAreCopiedToCacheAndQueued() = runBlocking {
        val db = buildTestDb()
        val connector = com.qiradev.cloudnest.engine.providers.FakeConnector()
        val upload = com.qiradev.cloudnest.engine.upload.UploadService(
            db = db,
            connectors = mapOf("google_drive" to connector),
            tokens = { com.qiradev.cloudnest.engine.providers.TokenPair("t", null, Long.MAX_VALUE) },
        )
        com.qiradev.cloudnest.engine.archive.ArchiveService(
            db,
            mapOf("google_drive" to connector),
            { com.qiradev.cloudnest.engine.providers.TokenPair("t", null, Long.MAX_VALUE) },
        ).ensureRootFolder()

        // Two incoming share URIs with readable streams (Robolectric shadow).
        val resolver = context.contentResolver
        val uri1 = android.net.Uri.parse("content://share/one.bin")
        val uri2 = android.net.Uri.parse("content://share/two.mp4")
        Shadows.shadowOf(resolver).registerInputStream(uri1, ByteArray(300).inputStream())
        Shadows.shadowOf(resolver).registerInputStream(uri2, ByteArray(700).inputStream())

        val jobIds = ShareUploads.enqueueFromShareUris(
            context,
            db,
            upload,
            listOf(uri1, uri2),
            db.folderDao().getRoot()!!.id,
        )

        assertEquals(2, jobIds.size)
        jobIds.forEach { jobId ->
            val job = db.transferJobDao().getById(jobId)!!
            assertEquals("upload", job.kind)
            val file = db.fileDao().getById(job.fileId!!)!!
            assertTrue(file.sizeBytes in listOf(300L, 700L))
            // The durable source is keyed by the file id and points at a cache copy.
            val meta = db.metaDao().getValue("upload_source:${job.fileId}")
            assertNotNull(meta)
            assertTrue(meta!!.contains("\"kind\":\"cache\""))
        }
        // Cache copies exist under the share staging area.
        val copies = CacheUploadSource.directory(context).listFiles().orEmpty()
        assertEquals(2, copies.size)
    }

    @Test
    fun terminalCleanupRemovesTheCacheCopy() = runBlocking {
        val db = buildTestDb()
        val connector = com.qiradev.cloudnest.engine.providers.FakeConnector()
        val upload = com.qiradev.cloudnest.engine.upload.UploadService(
            db = db,
            connectors = mapOf("google_drive" to connector),
            tokens = { com.qiradev.cloudnest.engine.providers.TokenPair("t", null, Long.MAX_VALUE) },
        )
        com.qiradev.cloudnest.engine.archive.ArchiveService(
            db,
            mapOf("google_drive" to connector),
            { com.qiradev.cloudnest.engine.providers.TokenPair("t", null, Long.MAX_VALUE) },
        ).ensureRootFolder()

        val uri = android.net.Uri.parse("content://share/gone.bin")
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, ByteArray(64).inputStream())
        val jobId = ShareUploads.enqueueFromShareUris(
            context,
            db,
            upload,
            listOf(uri),
            db.folderDao().getRoot()!!.id,
        ).single()
        val fileId = db.transferJobDao().getById(jobId)!!.fileId!!
        val copy = File(
            org.json.JSONObject(db.metaDao().getValue("upload_source:$fileId")!!).getString("path"),
        )
        assertTrue(copy.exists())

        // Done → cleanup drops the registry entry AND the cache copy.
        db.transferJobDao().update(
            db.transferJobDao().getById(jobId)!!
                .copy(status = com.qiradev.cloudnest.data.entity.JobStatus.DONE),
        )
        UploadDrivers.cleanupAfterTerminal(db, jobId, fileId)
        assertFalse(copy.exists())
        assertNull(db.metaDao().getValue("upload_source:$fileId"))
    }

    @Test
    fun safUrisQueueWithPersistedPermissionAndInjectedSize() = runBlocking {
        val db = buildTestDb()
        val connector = com.qiradev.cloudnest.engine.providers.FakeConnector()
        val upload = com.qiradev.cloudnest.engine.upload.UploadService(
            db = db,
            connectors = mapOf("google_drive" to connector),
            tokens = { com.qiradev.cloudnest.engine.providers.TokenPair("t", null, Long.MAX_VALUE) },
        )
        com.qiradev.cloudnest.engine.archive.ArchiveService(
            db,
            mapOf("google_drive" to connector),
            { com.qiradev.cloudnest.engine.providers.TokenPair("t", null, Long.MAX_VALUE) },
        ).ensureRootFolder()

        val uri = android.net.Uri.parse("content://saf/doc.pdf")
        val jobIds = ShareUploads.enqueueFromSafUris(
            context,
            db,
            upload,
            listOf(uri),
            db.folderDao().getRoot()!!.id,
            sizeOf = { 4_096L },
        )

        assertEquals(1, jobIds.size)
        val jobId = jobIds.single()
        val fileId = db.transferJobDao().getById(jobId)!!.fileId!!
        val file = db.fileDao().getById(fileId)!!
        assertEquals(4_096L, file.sizeBytes)
        val source = UploadSourceStore.restore(context, db, fileId)
        assertTrue(source is SafUploadSource)
        assertEquals(4_096L, source!!.sizeBytes())
    }
}

    /** Push latency on CI exceeds any fixed sleep — poll for the expected count instead. */
