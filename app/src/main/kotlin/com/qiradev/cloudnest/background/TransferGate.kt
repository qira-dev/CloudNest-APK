package com.qiradev.cloudnest.background

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes transfer driving inside the process (specs/05 §1): the foreground
 * service and [JobReviveWorker] both claim this gate before running or
 * recovering upload/mirror jobs, so a job is never driven twice and startup
 * recovery never requeues a live run.
 */
object TransferGate {
    val mutex = Mutex()

    @Volatile
    var transfersActive: Boolean = false
        private set

    /** Runs [block] holding the gate; [heavy] marks an actual transfer run (not just a peek). */
    suspend fun <T> own(heavy: Boolean = true, block: suspend () -> T): T = mutex.withLock {
        if (heavy) transfersActive = true
        try {
            block()
        } finally {
            if (heavy) transfersActive = false
        }
    }
}
