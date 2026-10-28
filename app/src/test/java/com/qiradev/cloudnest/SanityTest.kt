package com.qiradev.cloudnest

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Scaffold-level sanity check (step 0). Real engine tests are ported in later
 * steps per specs/10 section 2.
 */
class SanityTest {

    @Test
    fun arithmeticIsSane() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun stringConcatenationWorks() {
        assertEquals("CloudNest", "Cloud" + "Nest")
    }
}
