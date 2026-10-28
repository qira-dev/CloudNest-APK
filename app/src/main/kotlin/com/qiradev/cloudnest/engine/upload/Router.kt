package com.qiradev.cloudnest.engine.upload

/**
 * Routing (specs/02 §3): which chunk goes to which account bucket.
 * All-or-nothing — if a single chunk does not fit, the whole plan is rejected
 * and no partial reservations are made.
 */
object RoutingStrategy {
    const val CAPACITY_AWARE = "capacity_aware"
    const val BALANCED = "balanced"
}

class NoPlanException(val reason: String) : Exception(reason)

data class RoutePlan(
    /** Per chunk index (0-based) → representative account id that receives the chunk. */
    val assignments: List<String>,
    /** Per bucket id → total planned bytes (drives the reservations rows). */
    val plannedBytesByAccount: Map<String, Long>,
)

object Router {

    /**
     * Assigns [chunkSizes] (bytes, in chunk order) to eligible buckets.
     * Throws [NoPlanException] when any chunk cannot fit anywhere.
     */
    fun route(
        eligible: List<AccountCapacity>,
        chunkSizes: List<Long>,
        strategy: String = RoutingStrategy.CAPACITY_AWARE,
    ): RoutePlan {
        require(eligible.all { it.isEligible }) { "route() takes eligible buckets only" }
        require(strategy == RoutingStrategy.CAPACITY_AWARE || strategy == RoutingStrategy.BALANCED) {
            "unknown routing strategy: $strategy"
        }
        if (chunkSizes.isEmpty()) {
            return RoutePlan(emptyList(), emptyMap())
        }
        if (eligible.isEmpty()) throw NoPlanException(Ineligibility.NO_FREE_SPACE)

        val remaining = HashMap<String, Long>(eligible.size)
        val assigned = HashMap<String, Int>(eligible.size)
        val plannedBytes = LinkedHashMap<String, Long>()
        eligible.forEach { bucket ->
            remaining[bucket.representativeId] = bucket.usable.bytesOrMax
            assigned[bucket.representativeId] = 0
            plannedBytes[bucket.representativeId] = 0
        }

        val assignments = ArrayList<String>(chunkSizes.size)
        for (chunk in chunkSizes) {
            val chosen = when (strategy) {
                RoutingStrategy.CAPACITY_AWARE ->
                    // Largest remaining space first (specs/02 §3.1, the 5GB/70GB rule).
                    eligible.maxBy { remaining[it.representativeId]!! }.representativeId
                else ->
                    // Proportional spread (specs/02 §3.2): the bucket with the largest
                    // share-so-far ratio takes the next chunk.
                    eligible.maxBy {
                        remaining[it.representativeId]!! / (assigned[it.representativeId]!! + 1L)
                    }.representativeId
            }
            if (remaining[chosen]!! < chunk) throw NoPlanException(Ineligibility.NO_FREE_SPACE)
            remaining[chosen] = remaining[chosen]!! - chunk
            assigned[chosen] = assigned[chosen]!! + 1
            plannedBytes[chosen] = plannedBytes[chosen]!! + chunk
            assignments.add(chosen)
        }
        return RoutePlan(assignments, plannedBytes)
    }
}
