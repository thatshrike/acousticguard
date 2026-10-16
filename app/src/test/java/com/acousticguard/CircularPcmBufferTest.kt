package com.acousticguard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CircularPcmBufferTest {
    @Test
    fun retainsOnlyTheNewestPcmSamples() {
        val buffer = CircularPcmBuffer(4)
        buffer.writePcm16(shortArrayOf(1, 2, 3))
        assertArrayEquals(shortArrayOf(2, 3), buffer.snapshotPcm16())
        buffer.writePcm16(shortArrayOf(4, 5))
        assertArrayEquals(shortArrayOf(3, 4, 5), buffer.snapshotPcm16())
        assertEquals(4, buffer.sizeBytes())
    }
}
