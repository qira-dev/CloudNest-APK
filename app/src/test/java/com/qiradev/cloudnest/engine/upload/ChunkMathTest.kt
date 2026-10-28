package com.qiradev.cloudnest.engine.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** specs/10 ChunkMathTest — 0, 1 byte, exactly 64 MiB, +1, and the ladder. */
class ChunkMathTest {

    @Test
    fun emptyFileHasZeroChunks() {
        assertEquals(0, ChunkMath.chunkCount(0, 64))
    }

    @Test
    fun oneByteIsOneChunk() {
        assertEquals(1, ChunkMath.chunkCount(1, 64))
        assertEquals(1L, ChunkMath.chunkSizeAt(1, 64, 0))
    }

    @Test
    fun exactly64MibIsOneChunk() {
        val size = 64 * ChunkMath.MIB
        assertEquals(1, ChunkMath.chunkCount(size, 64))
        assertEquals(size, ChunkMath.chunkSizeAt(size, 64, 0))
    }

    @Test
    fun exactly64MibPlusOneIsTwoChunks() {
        val size = 64 * ChunkMath.MIB + 1
        assertEquals(2, ChunkMath.chunkCount(size, 64))
        assertEquals(64 * ChunkMath.MIB, ChunkMath.chunkSizeAt(size, 64, 0))
        assertEquals(1L, ChunkMath.chunkSizeAt(size, 64, 1))
    }

    @Test
    fun theOwners150MibExampleYields64Plus64Plus22() {
        val size = 150 * ChunkMath.MIB
        assertEquals(3, ChunkMath.chunkCount(size, 64))
        assertEquals(64 * ChunkMath.MIB, ChunkMath.chunkSizeAt(size, 64, 0))
        assertEquals(64 * ChunkMath.MIB, ChunkMath.chunkSizeAt(size, 64, 1))
        assertEquals(22 * ChunkMath.MIB, ChunkMath.chunkSizeAt(size, 64, 2))
    }

    @Test
    fun smallerChunkSizesSplitFurther() {
        val size = 150 * ChunkMath.MIB
        assertEquals(10, ChunkMath.chunkCount(size, 16))
        assertEquals(19, ChunkMath.chunkCount(size, 8))
    }

    @Test
    fun ladderGoes64To32To16To8ThenStops() {
        assertNull(ChunkMath.suggestSmallerChunkSizeMib(8))
        assertEquals(8, ChunkMath.suggestSmallerChunkSizeMib(16))
        assertEquals(16, ChunkMath.suggestSmallerChunkSizeMib(32))
        assertEquals(32, ChunkMath.suggestSmallerChunkSizeMib(64))
    }

    @Test
    fun invalidSizesAreRejected() {
        assertFalse(ChunkMath.isValidChunkSizeMib(4))
        assertFalse(ChunkMath.isValidChunkSizeMib(128))
        assertThrows(IllegalArgumentException::class.java) { ChunkMath.chunkCount(100, 4) }
        assertThrows(IllegalArgumentException::class.java) { ChunkMath.chunkSizeAt(100, 64, 5) }
        assertThrows(IllegalArgumentException::class.java) { ChunkMath.chunkCount(-1, 64) }
    }

    @Test
    fun allAllowedSizesWork() {
        ChunkMath.ALLOWED_CHUNK_SIZES_MIB.forEach { size ->
            assertTrue(ChunkMath.isValidChunkSizeMib(size))
            assertEquals(2, ChunkMath.chunkCount(size * ChunkMath.MIB + 1, size))
        }
    }
}
