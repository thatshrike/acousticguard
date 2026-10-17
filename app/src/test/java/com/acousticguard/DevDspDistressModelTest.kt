package com.acousticguard

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevDspDistressModelTest {
    private val model = DevDspDistressModel()

    @Test
    fun loudVocalScreamTriggersExplicitHelpAndVocalDistress() {
        // High volume voiced vocal scream (peak > 0.5, RMS > 0.08, ~600Hz formant)
        val screamAudio = ShortArray(8_000) { index ->
            val timeSeconds = index / 16_000.0
            val harmonic1 = 0.6 * sin(2.0 * PI * 600.0 * timeSeconds)
            val harmonic2 = 0.3 * sin(2.0 * PI * 1200.0 * timeSeconds)
            (Short.MAX_VALUE * (harmonic1 + harmonic2).coerceIn(-1.0, 1.0)).toInt().toShort()
        }

        val scores = model.infer(screamAudio)
        assertTrue("Expected explicit help keyword on loud scream", scores.explicitHelpKeyword)
        assertTrue("Expected strong distress label on loud scream", scores.strongDistressLabel)
        assertEquals(0.96f, scores.distressConfidence, 1.0e-3f)
        assertEquals(0.96f, scores.keywordConfidence, 1.0e-3f)
        assertEquals(0.96f, scores.combinedConfidence, 1.0e-3f)
        assertTrue(scores.detectedEvents.contains(DistressEvent.EXPLICIT_DISTRESS))
        assertTrue(scores.detectedEvents.contains(DistressEvent.STRONG_NON_VERBAL_DISTRESS))
    }

    @Test
    fun normalConversationalSpeechIsClassifiedAsBenign() {
        // Moderate conversation level (peak ~ 0.18, RMS ~ 0.025)
        val conversationAudio = ShortArray(8_000) { index ->
            val timeSeconds = index / 16_000.0
            val signal = 0.16 * sin(2.0 * PI * 220.0 * timeSeconds)
            (Short.MAX_VALUE * signal).toInt().toShort()
        }

        val scores = model.infer(conversationAudio)
        assertFalse("Normal speech must not be explicit help keyword", scores.explicitHelpKeyword)
        assertFalse("Normal speech must not be strong distress", scores.strongDistressLabel)
        assertTrue("Distress confidence must stay low for normal speech", scores.distressConfidence <= 0.18f)
        assertEquals(0f, scores.keywordConfidence, 1.0e-3f)
        assertTrue("Benign speech confidence must be high", scores.benignSpeechConfidence >= 0.70f)
        assertTrue(scores.detectedEvents.contains(DistressEvent.BENIGN_SPEECH))
    }

    @Test
    fun roomSilenceProducesLowAmbientScore() {
        val silence = ShortArray(8_000) { 0 }
        val scores = model.infer(silence)
        assertFalse(scores.explicitHelpKeyword)
        assertFalse(scores.strongDistressLabel)
        assertEquals(0.02f, scores.distressConfidence, 1.0e-3f)
        assertEquals(0f, scores.keywordConfidence, 1.0e-3f)
    }
}
