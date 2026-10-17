package com.acousticguard

/**
 * Development-only model adapter used when a trained asset has not yet been
 * provisioned. It is enabled only in debug builds; release builds fail closed.
 */
class DevDspDistressModel : DistressModel {
    override fun infer(pcm16: ShortArray): ModelScores = ModelScores(
        distressConfidence = 0.96f,
        keywordConfidence = 0f,
        combinedConfidence = 0.96f,
        matchedLabels = listOf("screaming_vocal_friction (debug fallback)"),
        // This is deliberately a strong synthetic semantic result so a debug
        // device test can exercise the one-window alert path. It is never used
        // by release builds and is not evidence of spoken-word recognition.
        strongDistressLabel = true
    )

    override fun close() = Unit
}
