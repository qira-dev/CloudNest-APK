package com.qiradev.cloudnest.engine.health

import com.qiradev.cloudnest.data.CloudNestDatabase

/** Provider health verdict (specs/06 §2.2). */
enum class Health {
    HEALTHY,
    DEGRADED,
    DOWN,
}

/**
 * Rolling 24-hour per-account call outcomes (specs/06 §2.1). Lives in memory;
 * `upload_attempts` replays into it at startup so a restart does not reset the
 * verdict. No dedicated table — the ledgers already carry the history.
 */
class HealthTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = WINDOW_MS,
) {
    data class Event(val ok: Boolean, val errorCode: String?, val kind: String, val at: Long)

    private val lock = Any()
    private val events = HashMap<String, ArrayDeque<Event>>()

    fun record(accountId: String, ok: Boolean, errorCode: String? = null, kind: String = "call") {
        synchronized(lock) {
            val now = clock()
            val queue = events.getOrPut(accountId) { ArrayDeque() }
            queue.addLast(Event(ok, errorCode, kind, now))
            while (queue.isNotEmpty() && now - queue.first().at > windowMs) queue.removeFirst()
            while (queue.size > MAX_EVENTS_PER_ACCOUNT) queue.removeFirst()
        }
    }

    fun recordSuccess(accountId: String, kind: String = "call") = record(accountId, ok = true, kind = kind)

    fun recordFailure(accountId: String, errorCode: String?, kind: String = "call") =
        record(accountId, ok = false, errorCode = errorCode, kind = kind)

    /**
     * Verdict (specs/06 §2.2):
     * Down — ≥10 consecutive failures, failure rate >50%, or zero successes in
     *   the window while work happened;
     * Degraded — ≥3 consecutive failures, or failure rate 10–50%;
     * Healthy — anything else, including no work at all.
     */
    fun verdict(accountId: String): Health {
        synchronized(lock) {
            val now = clock()
            val window = events[accountId].orEmpty().filter { now - it.at <= windowMs }
            if (window.isEmpty()) return Health.HEALTHY
            val failures = window.count { !it.ok }
            val successes = window.size - failures
            val rate = failures.toDouble() / window.size
            var streak = 0
            for (event in window.asReversed()) {
                if (event.ok) break
                streak++
            }
            return when {
                streak >= DOWN_STREAK || rate > DOWN_RATE || successes == 0 -> Health.DOWN
                streak >= DEGRADED_STREAK || rate >= DEGRADED_RATE -> Health.DEGRADED
                else -> Health.HEALTHY
            }
        }
    }

    fun lastSuccessAt(accountId: String): Long? {
        synchronized(lock) {
            val now = clock()
            return events[accountId].orEmpty()
                .filter { now - it.at <= windowMs && it.ok }
                .maxOfOrNull { it.at }
        }
    }

    /** Most recent failures for the diagnostics screen (specs/06 §3.3). */
    fun recentFailures(accountId: String, limit: Int): List<Event> {
        synchronized(lock) {
            val now = clock()
            return events[accountId].orEmpty()
                .filter { now - it.at <= windowMs && !it.ok }
                .takeLast(limit)
                .reversed()
        }
    }

    /** Replays recent upload attempts so a process restart keeps the verdict. */
    suspend fun hydrateFromDb(db: CloudNestDatabase) {
        val since = clock() - windowMs
        db.uploadAttemptDao().recentAttemptOutcomes(since).forEach { outcome ->
            if (outcome.result == "ok") {
                record(outcome.accountId, ok = true, kind = "upload")
            } else {
                record(outcome.accountId, ok = false, errorCode = outcome.errorCode, kind = "upload")
            }
        }
    }

    private companion object {
        const val WINDOW_MS = 24L * 60 * 60 * 1000
        const val MAX_EVENTS_PER_ACCOUNT = 500
        const val DEGRADED_STREAK = 3
        const val DOWN_STREAK = 10
        const val DEGRADED_RATE = 0.10
        const val DOWN_RATE = 0.50
    }
}
