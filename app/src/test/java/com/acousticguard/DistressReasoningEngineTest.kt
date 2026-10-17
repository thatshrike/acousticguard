package com.acousticguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistressReasoningEngineTest {
    private val engine = DistressReasoningEngine()

    @Test
    fun protectedPrecedenceIsAboveBenignOwnerVoiceSuppression() {
        val scores = ModelScores(
            distressConfidence = 0.96f,
            keywordConfidence = 0f,
            combinedConfidence = 0.96f,
            matchedLabels = listOf("duress", "conversation"),
            benignSpeechConfidence = 0.98f,
            labelScores = listOf(
                ModelLabelScore("duress", 0.96f),
                ModelLabelScore("conversation", 0.98f)
            )
        )
        val result = engine.decide(
            scores,
            features(candidate = true),
            OwnerVoiceMatch(enrolled = true, similarity = 0.99f, matched = true, threshold = 0.82f)
        )

        assertEquals(DistressReasoningPath.DURESS, result.path)
        assertTrue(result.shouldPreAlert)
        assertFalse(result.ownerVoiceSuppressed)
    }

    @Test
    fun matchedOwnerVoiceSuppressesOnlyTypedBenignSpeech() {
        val scores = ModelScores(
            distressConfidence = 0f,
            keywordConfidence = 0f,
            combinedConfidence = 0.9f,
            matchedLabels = listOf("conversation"),
            benignSpeechConfidence = 0.9f,
            labelScores = listOf(ModelLabelScore("conversation", 0.9f))
        )
        val result = engine.decide(
            scores,
            features(candidate = true),
            OwnerVoiceMatch(enrolled = true, similarity = 0.9f, matched = true, threshold = 0.82f)
        )

        assertEquals(DistressAction.NO_ACTION, result.action)
        assertEquals(DistressReasoningPath.BENIGN_SPEECH_OWNER_SUPPRESSED, result.path)
        assertTrue(result.suppressionAllowed)
    }

    @Test
    fun strongNonVerbalDistressOverridesOwnerVoiceAndBenignScore() {
        val scores = ModelScores(
            distressConfidence = 0.97f,
            keywordConfidence = 0f,
            combinedConfidence = 0.97f,
            matchedLabels = listOf("scream", "conversation"),
            strongDistressLabel = true,
            benignSpeechConfidence = 0.99f,
            labelScores = listOf(
                ModelLabelScore("scream", 0.97f),
                ModelLabelScore("conversation", 0.99f)
            )
        )
        val result = engine.decide(
            scores,
            features(candidate = true),
            OwnerVoiceMatch(enrolled = true, similarity = 0.99f, matched = true, threshold = 0.82f)
        )

        assertEquals(DistressReasoningPath.STRONG_NON_VERBAL_DISTRESS, result.path)
        assertTrue(result.shouldEscalate)
        assertFalse(result.suppressionAllowed)
    }

    @Test
    fun impactWithConcurrentMotionOverridesOwnerVoice() {
        val scores = ModelScores(
            distressConfidence = 0.93f,
            keywordConfidence = 0f,
            combinedConfidence = 0.93f,
            matchedLabels = listOf("physical_struggle_thud"),
            labelScores = listOf(ModelLabelScore("physical_struggle_thud", 0.93f))
        )
        val result = engine.decide(
            scores,
            features(
                candidate = true,
                impactDetected = true,
                accelerationPeak = 0.9f,
                gyroscopePeak = 0.8f,
                motionConcurrent = true
            ),
            OwnerVoiceMatch(enrolled = true, similarity = 0.99f, matched = true, threshold = 0.82f)
        )

        assertEquals(DistressReasoningPath.IMPACT_WITH_MOTION, result.path)
        assertTrue(result.shouldPreAlert)
        assertFalse(result.ownerVoiceSuppressed)
    }

    @Test
    fun explicitDistressOverridesOwnerVoice() {
        val scores = ModelScores(
            distressConfidence = 0f,
            keywordConfidence = 0.95f,
            combinedConfidence = 0.95f,
            matchedLabels = listOf("मदद करो"),
            explicitHelpKeyword = true,
            labelScores = listOf(ModelLabelScore("मदद करो", 0.95f))
        )
        val result = engine.decide(
            scores,
            features(candidate = true),
            OwnerVoiceMatch(enrolled = true, similarity = 0.99f, matched = true, threshold = 0.82f)
        )

        assertEquals(DistressReasoningPath.EXPLICIT_DISTRESS, result.path)
        assertTrue(result.shouldPreAlert)
    }

    @Test
    fun enrollmentIsBoundedAndClearRemovesTheProfile() {
        val store = BoundedOwnerVoiceProfileStore(maxEnrollmentSamples = 2, maxEmbeddingDimension = 4)
        assertTrue(store.addEnrollmentSample(floatArrayOf(1f, 0f)))
        assertTrue(store.addEnrollmentSample(floatArrayOf(0.9f, 0.1f)))
        assertFalse(store.addEnrollmentSample(floatArrayOf(0f, 1f)))
        assertEquals(2, store.status().sampleCount)
        assertTrue(store.match(floatArrayOf(1f, 0f)).matched)

        store.clear()
        assertFalse(store.status().enrolled)
        assertFalse(store.match(floatArrayOf(1f, 0f)).matched)
    }

    @Test
    fun taxonomyNormalizesIndianScriptsAndTransliteratedVariantsExactly() {
        val labels = listOf(
            "मदद" to IndianLanguage.HINDI,
            "সাহায্য" to IndianLanguage.BENGALI,
            "உதவி" to IndianLanguage.TAMIL,
            "సహాయం" to IndianLanguage.TELUGU,
            "ಸಹಾಯ" to IndianLanguage.KANNADA,
            "സഹായം" to IndianLanguage.MALAYALAM,
            "मदत" to IndianLanguage.MARATHI,
            "મદદ" to IndianLanguage.GUJARATI,
            "ਮਦਦ" to IndianLanguage.PUNJABI,
            "مدد" to IndianLanguage.URDU
        )
        labels.forEach { (raw, language) ->
            val normalized = DistressLabelTaxonomy.normalize(raw)
            assertEquals(DistressEvent.EXPLICIT_DISTRESS, normalized?.event)
            assertEquals(language, normalized?.language)
        }
        assertEquals(null, DistressLabelTaxonomy.normalize("helpful"))
        assertEquals(DistressEvent.EXPLICIT_DISTRESS, DistressLabelTaxonomy.normalize("madad")?.event)
        assertEquals(DistressEvent.EXPLICIT_DISTRESS, DistressLabelTaxonomy.normalize("mujhe bachao")?.event)
    }

    private fun features(
        candidate: Boolean,
        impactDetected: Boolean = false,
        accelerationPeak: Float = 0f,
        gyroscopePeak: Float = 0f,
        motionConcurrent: Boolean = false
    ) = TierOneFeatures(
        audio = AudioFeatures(0.2f, 0.2f, 0.4f, 0.03f, 0.08f, candidate),
        spectral = SpectralFeatures(0.5f, true, 1f),
        respiratory = RespiratoryFeatures(null, null, 0f, false),
        impact = ImpactFeatures(impactDetected, 0.5f, 5f, 4f, 5f, 30f, false),
        candidate = candidate,
        reasons = if (impactDetected) setOf(TierOneReason.IMPACT_TRANSIENT) else emptySet(),
        accelerationPeak = accelerationPeak,
        gyroscopePeak = gyroscopePeak,
        motionConcurrent = motionConcurrent
    )
}
