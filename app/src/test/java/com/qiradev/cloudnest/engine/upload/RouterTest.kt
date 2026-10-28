package com.qiradev.cloudnest.engine.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** specs/10 RouterTest — the owner's 5GB/70GB example, balanced spread, all-or-nothing. */
class RouterTest {

    private fun bucket(id: String, bytes: Long) = AccountCapacity(
        representativeId = id,
        memberIds = listOf(id),
        usable = Usable.Limited(bytes),
    )

    @Test
    fun theOwners5Gb70GbExampleSendsEverythingToTheBigPool() {
        // A = 5 GB, B = 70 GB, 200 MiB file with 64 MiB chunks → 4 chunks, all to B.
        val plan = Router.route(
            eligible = listOf(bucket("A", 5L * 1024 * 1024 * 1024), bucket("B", 70L * 1024 * 1024 * 1024)),
            chunkSizes = List(4) { 64 * ChunkMath.MIB },
            strategy = RoutingStrategy.CAPACITY_AWARE,
        )
        assertEquals(listOf("B", "B", "B", "B"), plan.assignments)
        assertEquals(4 * 64 * ChunkMath.MIB, plan.plannedBytesByAccount["B"])
        assertEquals(0L, plan.plannedBytesByAccount["A"])
    }

    @Test
    fun capacityAwareOverflowReachesTheSmallerPoolOnlyWhenNeeded() {
        // A(25) barely beats B(20) once; then B takes over while it stays larger.
        val chunk = 10L
        val plan = Router.route(
            eligible = listOf(bucket("A", 25L), bucket("B", 20L)),
            chunkSizes = List(3) { chunk },
            strategy = RoutingStrategy.CAPACITY_AWARE,
        )
        assertEquals(listOf("A", "B", "A"), plan.assignments)
    }

    @Test
    fun balancedSpreadsProportionallyToUsable() {
        val plan = Router.route(
            eligible = listOf(bucket("A", 100L), bucket("B", 300L)),
            chunkSizes = List(4) { 10L },
            strategy = RoutingStrategy.BALANCED,
        )
        assertEquals(1, plan.assignments.count { it == "A" })
        assertEquals(3, plan.assignments.count { it == "B" })
    }

    @Test
    fun allOrNothingRejectsTheWholePlan() {
        // A single chunk that fits nowhere rejects everything.
        assertThrows(NoPlanException::class.java) {
            Router.route(
                eligible = listOf(bucket("A", 100L)),
                chunkSizes = listOf(50L, 500L),
                strategy = RoutingStrategy.CAPACITY_AWARE,
            )
        }
    }

    @Test
    fun noEligibleBucketsIsNoPlan() {
        assertThrows(NoPlanException::class.java) {
            Router.route(eligible = emptyList(), chunkSizes = listOf(1L))
        }
    }

    @Test
    fun emptyFileGivesAnEmptyPlan() {
        val plan = Router.route(eligible = listOf(bucket("A", 100L)), chunkSizes = emptyList())
        assertEquals(0, plan.assignments.size)
    }

    @Test
    fun unlimitedBucketAlwaysHasRoom() {
        val plan = Router.route(
            eligible = listOf(bucket("A", 1L), AccountCapacity("U", listOf("U"), Usable.Unlimited)),
            chunkSizes = List(100) { 1L shl 40 },
            strategy = RoutingStrategy.CAPACITY_AWARE,
        )
        assertEquals(List(100) { "U" }, plan.assignments)
    }

    @Test
    fun unknownStrategyIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Router.route(eligible = listOf(bucket("A", 10L)), chunkSizes = listOf(1L), strategy = "chaos")
        }
    }
}
