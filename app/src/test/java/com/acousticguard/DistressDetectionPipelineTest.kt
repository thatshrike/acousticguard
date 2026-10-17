package com.acousticguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistressDetectionPipelineTest {
    @Test
    fun requiresTwoConsecutiveHighConfidenceWindows() {
        val model = object : DistressModel {
            override fun infer(pcm16: ShortArray) = ModelScores(0.9f, 0.1f, 0.9f, listOf("scream"))
            override fun close() = Unit
        }
        val pipeline = DistressDetectionPipeline(model)
        val audio = ShortArray(56_000)

        assertFalse(pipeline.evaluate(audio, true, 1_500L).validated)
        assertTrue(pipeline.evaluate(audio, true, 3_000L).validated)
        pipeline.close()
    }

    @Test
    fun lowConfidenceWindowResetsTheConfirmationCount() {
        var confidence = 0.9f
        val model = object : DistressModel {
            override fun infer(pcm16: ShortArray) = ModelScores(confidence, 0f, confidence, emptyList())
            override fun close() = Unit
        }
        val pipeline = DistressDetectionPipeline(model)
        val audio = ShortArray(56_000)
        pipeline.evaluate(audio, true, 1_500L)
        confidence = 0.2f
        assertFalse(pipeline.evaluate(audio, true, 3_000L).validated)
        pipeline.close()
    }

    @Test
    fun fusedSpectralAndRespiratoryEvidenceUsesOnePointFiveSecondFastTrack() {
        val model = object : DistressModel {
            override fun infer(pcm16: ShortArray) = ModelScores(0.9f, 0f, 0.9f, listOf("screaming_vocal_friction"))
            override fun close() = Unit
        }
        val pipeline = DistressDetectionPipeline(model)
        val features = TierOneFeatures(
            audio = AudioFeatures(0.2f, 0.2f, 0.4f, 0.03f, 0.08f, true),
            spectral = SpectralFeatures(0.5f, true, 1f),
            respiratory = RespiratoryFeatures(0.7f, 3_000L, 0.2f, true),
            impact = ImpactFeatures(false, 0f, 0f, 0f, null, null, false),
            candidate = true,
            reasons = setOf(TierOneReason.HIGH_FREQUENCY, TierOneReason.RESPIRATORY_PERIODICITY)
        )
        val audio = ShortArray(56_000)

        assertFalse(pipeline.evaluate(audio, features, 750L).validated)
        assertTrue(pipeline.evaluate(audio, features, 1_500L).validated)
        pipeline.close()
    }

    @Test
    fun rapidProbeDoesNotConsumeTheOrdinaryTwoWindowConfirmationCounter() {
        val model = object : DistressModel {
            override fun infer(pcm16: ShortArray) = ModelScores(
                distressConfidence = 0.95f,
                keywordConfidence = 0.95f,
                combinedConfidence = 0.95f,
                matchedLabels = listOf("help"),
                explicitHelpKeyword = true
            )

            override fun close() = Unit
        }
        val pipeline = DistressDetectionPipeline(model)
        val features = TierOneFeatures(
            audio = AudioFeatures(0.2f, 0.2f, 0.4f, 0.03f, 0.08f, true),
            spectral = SpectralFeatures(0.5f, true, 1f),
            respiratory = RespiratoryFeatures(null, null, 0f, false),
            impact = ImpactFeatures(false, 0f, 0f, 0f, null, null, false),
            candidate = true,
            reasons = setOf(TierOneReason.HIGH_FREQUENCY)
        )
        val rapid = pipeline.evaluateRapid(ShortArray(8_000), features, 1_000L)
        assertTrue(rapid.validated)

        val standard = pipeline.evaluate(ShortArray(56_000), features, 1_500L)
        assertFalse(standard.validated)
        assertTrue(standard.consecutiveQualifyingWindows == 1)
        pipeline.close()
    }

    @Test
    fun ownerVoiceOnlySuppressesTypedBenignSpeech() {
        val model = object : DistressModel {
            override fun infer(pcm16: ShortArray) = ModelScores(
                distressConfidence = 0.02f,
                keywordConfidence = 0f,
                combinedConfidence = 0.9f,
                matchedLabels = listOf("conversation"),
                benignSpeechConfidence = 0.95f,
                labelScores = listOf(ModelLabelScore("conversation", 0.95f))
            )

            override fun close() = Unit
        }
        val pipeline = DistressDetectionPipeline(
            model = model,
            reasoningEngine = DistressReasoningEngine(),
            ownerVoiceMatcher = { _, _ ->
                OwnerVoiceMatch(enrolled = true, similarity = 0.95f, matched = true)
            }
        )
        val features = TierOneFeatures(
            audio = AudioFeatures(0.2f, 0.2f, 0.4f, 0.03f, 0.08f, true),
            spectral = SpectralFeatures(0.5f, true, 1f),
            respiratory = RespiratoryFeatures(null, null, 0f, false),
            impact = ImpactFeatures(false, 0f, 0f, 0f, null, null, false),
            candidate = true,
            reasons = setOf(TierOneReason.RMS_ZCR)
        )

        val result = pipeline.evaluate(ShortArray(56_000), features, 1_500L)
        assertFalse(result.validated)
        assertTrue(result.reasoningPath == DistressReasoningPath.BENIGN_SPEECH_OWNER_SUPPRESSED)
        pipeline.close()
    }
}
