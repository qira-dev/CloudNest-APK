package com.qiradev.cloudnest.engine.upload

import com.qiradev.cloudnest.data.entity.ProviderAccountEntity
import com.qiradev.cloudnest.data.entity.ProviderAccountStatus
import com.qiradev.cloudnest.data.entity.QuotaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** specs/10 CapacityTest — current/stale/unknown, reserve, reservation, unlimited, scope. */
class CapacityTest {

    private fun account(
        id: String,
        free: Long? = 1_000L,
        used: Long? = 0L,
        status: String = ProviderAccountStatus.CONNECTED,
        quotaStatus: String = QuotaStatus.CURRENT,
        unlimited: Int = 0,
        scopeKey: String? = null,
    ) = ProviderAccountEntity(
        id = id,
        provider = "google_drive",
        label = id,
        status = status,
        encryptedTokens = null,
        tokenExpiresAt = 0,
        quotaTotalBytes = free?.let { it + (used ?: 0) },
        quotaUsedBytes = used,
        quotaUnlimited = unlimited,
        quotaStatus = quotaStatus,
        quotaObservedAt = 1,
        quotaScopeKey = scopeKey,
        rootPath = null,
        createdAt = 0,
        lastUsedAt = 0,
    )

    @Test
    fun usableIsFreeMinusReserveMinusReservationsMinusMargin() {
        val capacities = Capacity.evaluate(
            accounts = listOf(account("a", free = 1_000L)),
            reservationsByAccount = mapOf("a" to 100L),
            reservePerAccountBytes = 50L,
            safetyMarginBytes = 30L,
        )
        assertEquals(820L, (capacities.single().usable as Usable.Limited).bytes)
        assertNull(capacities.single().ineligibilityReason)
    }

    @Test
    fun neverBelowZero() {
        val capacities = Capacity.evaluate(
            accounts = listOf(account("a", free = 100L)),
            reservationsByAccount = mapOf("a" to 500L),
            reservePerAccountBytes = 0,
            safetyMarginBytes = 0,
        )
        assertEquals(0L, (capacities.single().usable as Usable.Limited).bytes)
        assertEquals(Ineligibility.NO_FREE_SPACE, capacities.single().ineligibilityReason)
    }

    @Test
    fun staleAndUnknownAreNeverTrusted() {
        val capacities = Capacity.evaluate(
            accounts = listOf(
                account("stale", quotaStatus = QuotaStatus.STALE),
                account("unknown", quotaStatus = QuotaStatus.UNKNOWN),
            ),
            reservationsByAccount = emptyMap(),
            reservePerAccountBytes = 0,
            safetyMarginBytes = 0,
        )
        assertEquals(Ineligibility.QUOTA_STALE, capacities.first { it.representativeId == "stale" }.ineligibilityReason)
        assertEquals(Ineligibility.QUOTA_UNKNOWN, capacities.first { it.representativeId == "unknown" }.ineligibilityReason)
        assertTrue(capacities.none { it.isEligible })
    }

    @Test
    fun missingTotalIsUnknown() {
        val capacities = Capacity.evaluate(
            accounts = listOf(account("a", free = null, used = null)),
            reservationsByAccount = emptyMap(),
            reservePerAccountBytes = 0,
            safetyMarginBytes = 0,
        )
        assertEquals(Ineligibility.QUOTA_UNKNOWN, capacities.single().ineligibilityReason)
    }

    @Test
    fun disconnectedAccountsAreExcluded() {
        val capacities = Capacity.evaluate(
            accounts = listOf(account("a", status = ProviderAccountStatus.DISCONNECTED)),
            reservationsByAccount = emptyMap(),
            reservePerAccountBytes = 0,
            safetyMarginBytes = 0,
        )
        assertEquals(Ineligibility.ACCOUNT_DISCONNECTED, capacities.single().ineligibilityReason)
    }

    @Test
    fun unlimitedGetsTheSentinelWithoutFabricatedNumbers() {
        val capacities = Capacity.evaluate(
            accounts = listOf(account("a", unlimited = 1)),
            reservationsByAccount = mapOf("a" to 999L),
            reservePerAccountBytes = 999,
            safetyMarginBytes = 999,
        )
        assertEquals(Usable.Unlimited, capacities.single().usable)
        assertNull(capacities.single().ineligibilityReason)
    }

    @Test
    fun sameScopeKeyAccountsCountTheirPoolOnce() {
        // Two logins of the same Gmail: free 100 each, reservations 30 + 20 in the pool.
        val capacities = Capacity.evaluate(
            accounts = listOf(
                account("a1", free = 100L, scopeKey = "pool-1"),
                account("a2", free = 100L, scopeKey = "pool-1"),
            ),
            reservationsByAccount = mapOf("a1" to 30L, "a2" to 20L),
            reservePerAccountBytes = 0,
            safetyMarginBytes = 0,
        )
        assertEquals(1, capacities.size)
        // Counted once: 100 − 50 = 50, NOT (100−30) + (100−20).
        assertEquals(50L, (capacities.single().usable as Usable.Limited).bytes)
    }

    @Test
    fun poolUsesTheTightestFreeObservation() {
        val capacities = Capacity.evaluate(
            accounts = listOf(
                account("a1", free = 100L, scopeKey = "p"),
                account("a2", free = 80L, scopeKey = "p"),
            ),
            reservationsByAccount = emptyMap(),
            reservePerAccountBytes = 0,
            safetyMarginBytes = 0,
        )
        assertEquals(80L, (capacities.single().usable as Usable.Limited).bytes)
    }

    @Test
    fun accountsWithoutScopeKeyAreTheirOwnPool() {
        val capacities = Capacity.evaluate(
            accounts = listOf(account("a1", free = 100L), account("a2", free = 200L)),
            reservationsByAccount = emptyMap(),
            reservePerAccountBytes = 0,
            safetyMarginBytes = 0,
        )
        assertEquals(2, capacities.size)
        assertTrue(capacities.all { it.isEligible })
        assertFalse(capacities.any { it.memberIds.size > 1 })
    }
}
