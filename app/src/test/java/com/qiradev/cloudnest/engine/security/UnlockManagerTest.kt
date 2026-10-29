package com.qiradev.cloudnest.engine.security

import com.qiradev.cloudnest.data.buildTestDb
import com.qiradev.cloudnest.data.testAccount
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** specs/08 §2 — unlock hash storage, verification and the 5-wrong lockout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnlockManagerTest {

    private fun manager(clock: () -> Long = System::currentTimeMillis): UnlockManager {
        val db = buildTestDb()
        return UnlockManager(db, clock = clock)
    }

    @Test
    fun notConfiguredUntilSetup(): Unit = runBlocking {
        val unlock = manager()
        assertEquals(UnlockResult.NotConfigured, unlock.verify("whatever"))
        assertFalse(unlock.isConfigured())
    }

    @Test
    fun correctPasswordUnlocks(): Unit = runBlocking {
        val unlock = manager()
        unlock.setUnlockPassword("correct horse")
        assertEquals(UnlockResult.Unlocked, unlock.verify("correct horse"))
        assertTrue(unlock.isConfigured())
    }

    @Test
    fun storesHashNotThePassword(): Unit = runBlocking {
        val db = buildTestDb()
        val unlock = UnlockManager(db)
        unlock.setUnlockPassword("secret-pass")
        val stored = db.settingsDao().getValue("unlock_hash")!!
        assertFalse(stored.contains("secret-pass"))
        assertTrue(stored.startsWith("pbkdf2_sha256$"))
        // A second setup produces a fresh salt — the stored value differs.
        unlock.setUnlockPassword("secret-pass")
        val storedAgain = db.settingsDao().getValue("unlock_hash")!!
        assertNotEquals(stored, storedAgain)
    }

    @Test
    fun wrongPasswordReportsAttemptsLeft(): Unit = runBlocking {
        val unlock = manager()
        unlock.setUnlockPassword("real")
        val wrong = unlock.verify("nope") as UnlockResult.WrongPassword
        assertEquals(4, wrong.attemptsLeft)
    }

    @Test
    fun fiveWrongAnswersLockOutForThirtySeconds(): Unit = runBlocking {
        var now = 1_000_000L
        val unlock = manager(clock = { now })
        unlock.setUnlockPassword("real")
        repeat(4) { assertTrue(unlock.verify("bad") is UnlockResult.WrongPassword) }
        // Fifth wrong answer → 30-second lockout.
        val locked = unlock.verify("bad")
        assertTrue(locked is UnlockResult.LockedOut)
        assertEquals(30_000L, (locked as UnlockResult.LockedOut).retryAfterMs)
        // Even the right password waits out the lockout.
        now += 10_000
        assertTrue(unlock.verify("real") is UnlockResult.LockedOut)
        // After the lockout passes, the correct password unlocks.
        now += 25_000
        assertEquals(UnlockResult.Unlocked, unlock.verify("real"))
    }

    @Test
    fun successResetsTheFailureCounter(): Unit = runBlocking {
        var now = 1_000_000L
        val unlock = manager(clock = { now })
        unlock.setUnlockPassword("real")
        repeat(4) { unlock.verify("bad") }
        assertEquals(UnlockResult.Unlocked, unlock.verify("real"))
        // Four earlier failures must not carry over: a fresh wrong streak needs 5 again.
        repeat(4) { assertTrue(unlock.verify("bad") is UnlockResult.WrongPassword) }
        now += 60_000
        assertTrue(unlock.verify("bad") is UnlockResult.LockedOut)
    }
}
