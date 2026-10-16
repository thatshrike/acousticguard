package com.acousticguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMathTest {
    @Test
    fun resamplerProducesExpectedLength() {
        val result = PcmResampler.to16Khz(ShortArray(48_000), 48_000)
        assertEquals(16_000, result.size)
    }

    @Test
    fun resamplerDoesNotClipTheOutputDomain() {
        val result = PcmResampler.to16Khz(ShortArray(48_000) { Short.MAX_VALUE }, 48_000)
        assertTrue(result.all { it == Short.MAX_VALUE })
    }
}
