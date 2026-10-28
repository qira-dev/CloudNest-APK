package com.qiradev.cloudnest.engine.download

import com.qiradev.cloudnest.data.entity.FileChunkEntity
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import java.io.OutputStream

/**
 * Ordered assembly (specs/03 §2+§3): up to [maxInFlight] chunks download in
 * parallel but bytes are always written in chunk_index order; the file is
 * never assembled in memory.
 */
object Assembler {

    suspend fun assemble(
        target: OutputStream,
        chunks: List<FileChunkEntity>,
        maxInFlight: Int,
        shouldContinue: suspend () -> Boolean,
        fetch: suspend (FileChunkEntity) -> ByteArray,
    ): Long = coroutineScope {
        require(maxInFlight >= 1) { "maxInFlight must be >= 1" }
        val inFlight = ArrayDeque<Deferred<ByteArray>>()
        var written = 0L

        suspend fun drainOne() {
            val bytes = inFlight.removeFirst().await()
            target.write(bytes)
            written += bytes.size
        }

        try {
            for (chunk in chunks) {
                if (!shouldContinue()) return@coroutineScope written
                while (inFlight.size >= maxInFlight) drainOne()
                inFlight += async(Dispatchers.IO) { fetch(chunk) }
            }
            while (inFlight.isNotEmpty()) drainOne()
        } catch (e: Throwable) {
            inFlight.forEach { it.cancelAndJoin() }
            throw e
        }
        written
    }
}
