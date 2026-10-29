package com.qiradev.cloudnest.engine.providers

import com.qiradev.cloudnest.data.buildTestDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The meta-backed OAuth flow store (specs/04 §2): states and PKCE verifiers
 * survive instance churn over the same database — the property the connect
 * flow depends on, since the redirect is completed by a later graph build
 * than the one that started it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OAuthFlowStoreTest {

    private var nowMs: Long = 1_000_000L

    @Test
    fun stateSurvivesInstanceChurnOnTheSameDb(): Unit = runBlocking {
        val db = buildTestDb()
        val first = OAuthStateStore(db) { nowMs }
        val state = first.newOauthState("dropbox")

        val second = OAuthStateStore(db) { nowMs }
        assertEquals("dropbox", second.validateAndConsume(state))
        assertNull(second.validateAndConsume(state))
    }

    @Test
    fun metaBackedStateIsSingleUseAndExpires(): Unit = runBlocking {
        val db = buildTestDb()
        val store = OAuthStateStore(db) { nowMs }
        val state = store.newOauthState("onedrive")

        nowMs += 2 * 60 * 1000L + 1
        assertNull(store.validateAndConsume(state))
    }

    @Test
    fun verifierIsSingleUseAcrossInstances(): Unit = runBlocking {
        val db = buildTestDb()
        OAuthStateStore(db) { nowMs }.saveCodeVerifier("pcloud", "verifier-1")

        val second = OAuthStateStore(db) { nowMs }
        assertEquals("verifier-1", second.takeCodeVerifier("pcloud"))
        assertNull(second.takeCodeVerifier("pcloud"))
    }

    @Test
    fun memoryModeKeepsWorkingForTests(): Unit = runBlocking {
        val store = OAuthStateStore { nowMs }
        val state = store.newOauthState("google_drive")
        assertEquals("google_drive", store.validateAndConsume(state))
        assertNull(store.validateAndConsume(state))
    }
}
