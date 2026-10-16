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
}
