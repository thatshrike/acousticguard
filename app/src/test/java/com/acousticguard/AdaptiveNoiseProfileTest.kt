package com.acousticguard

import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveNoiseProfileTest {
    @Test
    fun steadyNonCandidateNoiseRaisesTheLearnedFloor() {
        val profile = AdaptiveNoiseProfile()
        val before = profile.snapshot()
        repeat(300) { profile.observe(rms = 0.25f, highFrequencyRatio = 0.4f, candidate = false) }
        val after = profile.snapshot()
        assertTrue(after.rms > before.rms)
        assertTrue(after.rmsCandidateFloor > before.rmsCandidateFloor)
    }

    @Test
    fun candidateFramesCannotRaiseTheNoiseFloor() {
        val profile = AdaptiveNoiseProfile()
        val before = profile.snapshot()
        repeat(100) { profile.observe(rms = 0.9f, highFrequencyRatio = 0.9f, candidate = true) }
        val after = profile.snapshot()
        assertTrue(after.rms == before.rms)
        assertTrue(after.highFrequencyRatio == before.highFrequencyRatio)
    }
}
