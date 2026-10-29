package com.qiradev.cloudnest.ui

import com.qiradev.cloudnest.data.buildTestDb
import com.qiradev.cloudnest.data.entity.AuditEventType
import com.qiradev.cloudnest.data.SettingsKeys
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings screen controller (specs/09 §13 step-8 scope): writes reach the
 * `settings` table, every change is audited as `settings_changed`, and the
 * controller state follows. The controller mutates from its own scope thread,
 * so assertions poll with a deadline instead of sleeping blindly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsUiControllerTest {

    private val dispatcher = Executors.newSingleThreadExecutor().also {
        Runtime.getRuntime().addShutdownHook(Thread { it.shutdown() })
    }

    private fun <T> poll(timeoutMs: Long = 5_000, check: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            check()?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    @Test
    fun loadsSpecDefaultsIntoState() = runBlocking {
        val controller = SettingsUiController(buildTestDb(), CoroutineScope(dispatcher.asCoroutineDispatcher()))
        controller.load()
        // With nothing stored the controller state is the spec defaults — both
        // before and after the async load resolves to the same values.
        val state = controller.state
        assertEquals(true, state.wifiOnlyUpload)
        assertEquals(64, state.chunkSizeMib)
        assertEquals(true, state.mirrorEnabled)
        assertEquals(7, state.trashRetentionDays)
    }

    @Test
    fun writesReachSettingsAndAreAudited() = runBlocking {
        val db = buildTestDb()
        val controller = SettingsUiController(
            db,
            CoroutineScope(dispatcher.asCoroutineDispatcher()),
            clock = { 1_000_000L },
        )
        controller.setWifiOnlyUpload(false)
        controller.setChunkSizeMib(16)
        controller.setMirrorEnabled(false)
        controller.setTrashRetentionDays(14)

        // Poll on the (non-suspend) state: the controller writes from its scope thread.
        val settled = poll { if (controller.state.trashRetentionDays == 14) controller.state else null }

        assertEquals(false, settled.wifiOnlyUpload)
        assertEquals(16, settled.chunkSizeMib)
        runBlocking {
            assertEquals("false", db.settingsDao().getValue(SettingsKeys.WIFI_ONLY_UPLOAD))
            assertEquals("16", db.settingsDao().getValue(SettingsKeys.CHUNK_SIZE_MIB))
            assertEquals("false", db.settingsDao().getValue(SettingsKeys.MIRROR_ENABLED))
            assertEquals("14", db.settingsDao().getValue(SettingsKeys.TRASH_RETENTION_DAYS))

            val changed = db.auditEventDao().latest(20).filter { it.type == AuditEventType.SETTINGS_CHANGED }
            assertEquals(4, changed.size)
            assertTrue(changed.any { it.detailJson.contains(SettingsKeys.WIFI_ONLY_UPLOAD) })
        }
    }
}
