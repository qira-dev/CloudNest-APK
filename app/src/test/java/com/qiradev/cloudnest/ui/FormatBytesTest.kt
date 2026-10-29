package com.qiradev.cloudnest.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * formatBytes regression — an off-by-one in the unit ladder displayed every
 * size >= 1 KiB 1024x larger (3.2 GB rendered as "3.2 TB" on diagnostics).
 */
class FormatBytesTest {

    @Test fun `bytes below 1 KiB stay in bytes`() {
        assertEquals("45 B", formatBytes(45))
    }

    @Test fun `kibibyte boundary`() {
        assertEquals("1.0 KB", formatBytes(1024))
    }

    @Test fun `gigabyte value stays in gigabytes`() {
        assertEquals("3.2 GB", formatBytes(3_435_973_837L))
    }

    @Test fun `15 gib total quota stays in gigabytes`() {
        assertEquals("15.0 GB", formatBytes(16_106_127_360L))
    }

    @Test fun `very large value caps at terabytes`() {
        assertEquals("5120.0 TB", formatBytes(5_629_499_534_213_120L))
    }
}
