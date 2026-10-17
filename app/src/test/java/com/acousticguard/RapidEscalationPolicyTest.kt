package com.acousticguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RapidEscalationPolicyTest {
    @Test
    fun oneExplicitHelpKeywordCanRapidValidate() {
        val features = features(
            reasons = setOf(TierOneReason.HIGH_FREQUENCY),
            impactDetected = false
        )
        val raw = ModelScores(0.2f, 0.95f, 0.95f, listOf("help"), explicitHelpKeyword = true)
        val decision = RapidEscalationPolicy.decide(features, raw, raw)
        assertTrue(decision.shouldPreAlert)
        assertEquals(RapidEscalationReason.EXPLICIT_HELP_KEYWORD, decision.reason)
    }

    @Test
    fun substringLookalikeOrWeakKeywordDoesNotRapidValidate() {
        val features = features(setOf(TierOneReason.HIGH_FREQUENCY), impactDetected = false)
        val raw = ModelScores(0.2f, 0.86f, 0.86f, listOf("helpful"), explicitHelpKeyword = false)
        assertFalse(RapidEscalationPolicy.decide(features, raw, raw).shouldPreAlert)
    }

    @Test
    fun oneVeryStrongVocalDistressWindowCanRapidValidate() {
        val features = features(setOf(TierOneReason.HIGH_FREQUENCY), impactDetected = false)
        val raw = ModelScores(
            distressConfidence = 0.97f,
            keywordConfidence = 0f,
            combinedConfidence = 0.97f,
            matchedLabels = listOf("scream"),
            strongDistressLabel = true
        )
        assertEquals(
            RapidEscalationReason.STRONG_VOCAL_DISTRESS,
            RapidEscalationPolicy.decide(features, raw, raw).reason
        )
    }

    @Test
    fun strongImpactRequiresBothMotionSensorsAndModelEvidence() {
        val features = features(
            reasons = setOf(TierOneReason.IMPACT_TRANSIENT),
            impactDetected = true,
            accelerationPeak = 0.9f,
            gyroscopePeak = 0.8f,
            motionConcurrent = true
        )
        val raw = ModelScores(
            distressConfidence = 0.95f,
            keywordConfidence = 0f,
            combinedConfidence = 0.95f,
            matchedLabels = listOf("physical_struggle_thud"),
            strongDistressLabel = true
        )
        assertEquals(
            RapidEscalationReason.STRONG_IMPACT_WITH_MOTION,
            RapidEscalationPolicy.decide(features, raw, raw).reason
        )

        assertFalse(
            RapidEscalationPolicy.decide(
                features.copy(gyroscopePeak = 0.1f),
                raw,
                raw
            ).shouldPreAlert
        )
    }

    private fun features(
        reasons: Set<TierOneReason>,
        impactDetected: Boolean,
        accelerationPeak: Float = 0f,
        gyroscopePeak: Float = 0f,
        motionConcurrent: Boolean = false
    ) = TierOneFeatures(
        audio = AudioFeatures(0.2f, 0.2f, 0.4f, 0.03f, 0.08f, true),
        spectral = SpectralFeatures(0.5f, true, 1f),
        respiratory = RespiratoryFeatures(null, null, 0f, false),
        impact = ImpactFeatures(impactDetected, 0.5f, 5f, 4f, 5f, 30f, false),
        candidate = true,
        reasons = reasons,
        accelerationPeak = accelerationPeak,
        gyroscopePeak = gyroscopePeak,
        motionConcurrent = motionConcurrent
    )
}
