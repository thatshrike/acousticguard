package com.acousticguard

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TierOneAnalyzerTest {
    @Test
    fun highFrequencyToneProducesSpectralEvidence() {
        val analyzer = SpectralAnalyzer()
        val high = sineWave(6_000f, 8_000)
        val low = sineWave(500f, 8_000)

        val highResult = analyzer.analyze(high, 16_000)
        val lowResult = analyzer.analyze(low, 16_000)

        assertTrue(highResult.valid)
        assertTrue((highResult.highFrequencyRatio ?: 0f) >= 0.35f)
        assertTrue(lowResult.valid)
        assertTrue((lowResult.highFrequencyRatio ?: 1f) < 0.35f)
    }

    @Test
    fun respiratoryDetectorFindsThreeSecondEnvelope() {
        val detector = RespiratoryPeriodicityDetector()
        var result = RespiratoryFeatures(null, null, 0f, false)
        repeat(32) { frameIndex ->
            val frame = ShortArray(8_000) { sampleIndex ->
                val timeSeconds = (frameIndex * 8_000 + sampleIndex) / 16_000.0
                val modulation = 0.16 + 0.12 * (0.5 + 0.5 * sin(2.0 * PI * timeSeconds / 3.0))
                (Short.MAX_VALUE * modulation * sin(2.0 * PI * 300.0 * timeSeconds)).toInt().toShort()
            }
            result = detector.update(frame, 16_000)
        }
        assertTrue(result.valid)
        assertNotNull(result.periodMs)
        assertTrue(kotlin.math.abs(result.periodMs!! - 3_000L) <= 250L)
    }

    @Test
    fun shortImpactIsDetectedButSustainedToneIsNot() {
        val detector = ImpactTransientDetector()
        val impact = ShortArray(8_000)
        for (i in 4_000 until 4_160) impact[i] = (Short.MAX_VALUE * 0.8).toInt().toShort()
        val impactResult = detector.analyze(impact, 16_000, 0.01f)
        assertTrue(impactResult.detected)

        detector.reset()
        val sustained = sineWave(500f, 8_000, 0.8f)
        val sustainedResult = detector.analyze(sustained, 16_000, 0.01f)
        assertFalse(sustainedResult.detected)
    }

    private fun sineWave(frequency: Float, samples: Int, amplitude: Float = 0.5f): ShortArray =
        ShortArray(samples) { index ->
            (Short.MAX_VALUE * amplitude * sin(2.0 * PI * frequency * index / 16_000.0)).toInt().toShort()
        }
}
