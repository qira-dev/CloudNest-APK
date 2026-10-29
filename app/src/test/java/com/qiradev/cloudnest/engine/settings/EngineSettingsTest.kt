package com.qiradev.cloudnest.engine.settings

import com.qiradev.cloudnest.data.buildTestDb
import com.qiradev.cloudnest.data.entity.SettingsEntity
import com.qiradev.cloudnest.data.SettingsKeys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** specs/01 §1.2 + specs/02 §1/§5 — settings rows reach the engine values. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineSettingsTest {

    @Test
    fun defaultsWhenNothingStored(): Unit = runBlocking {
        val db = buildTestDb()
        val settings = EngineSettings.loadUploadSettings(db)
        assertEquals(64, settings.chunkSizeMib)
        assertEquals(0L, settings.reservePerAccountBytes)
        assertEquals(0L, settings.safetyMarginBytes)
        // specs/06 §1.1: mirroring defaults to on.
        assertTrue(settings.mirrorEnabled)
        assertTrue(EngineSettings.wifiOnlyUpload(db))
    }

    @Test
    fun storedValuesReachTheEngine(): Unit = runBlocking {
        val db = buildTestDb()
        val dao = db.settingsDao()
        dao.upsert(SettingsEntity(SettingsKeys.CHUNK_SIZE_MIB, "16"))
        dao.upsert(SettingsEntity(SettingsKeys.MIRROR_ENABLED, "false"))
        dao.upsert(SettingsEntity(SettingsKeys.WIFI_ONLY_UPLOAD, "false"))
        dao.upsert(SettingsEntity(SettingsKeys.RESERVE_PER_ACCOUNT_BYTES, "52428800"))

        val settings = EngineSettings.loadUploadSettings(db)
        assertEquals(16, settings.chunkSizeMib)
        assertFalse(settings.mirrorEnabled)
        assertEquals(52_428_800L, settings.reservePerAccountBytes)
        assertFalse(EngineSettings.wifiOnlyUpload(db))
    }

    @Test
    fun invalidChunkSizeFallsBackToTheDefault(): Unit = runBlocking {
        val db = buildTestDb()
        db.settingsDao().upsert(SettingsEntity(SettingsKeys.CHUNK_SIZE_MIB, "20"))
        assertEquals(64, EngineSettings.loadUploadSettings(db).chunkSizeMib)
    }

    @Test
    fun wifiOnlyGateBlocksOutsideWifiAndPassesWithoutThePolicy() {
        var onWifi = false
        val gate = WifiOnlyNetworkGate(wifiOnly = true, isOnWifi = { onWifi })
        // No Wi-Fi → transfers must not run (specs/02 §5).
        assertFalse(gate.isSatisfied())
        onWifi = true
        assertTrue(gate.isSatisfied())

        // Policy off → any network is fine.
        onWifi = false
        val unrestricted = WifiOnlyNetworkGate(wifiOnly = false, isOnWifi = { onWifi })
        assertTrue(unrestricted.isSatisfied())
    }
}
