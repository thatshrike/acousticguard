package com.acousticguard

/**
 * Development-only model adapter used when a trained asset has not yet been
 * provisioned. It is enabled only in debug builds; release builds fail closed.
 */
class DevDspDistressModel : DistressModel {
    override fun infer(pcm16: ShortArray): ModelScores = ModelScores(
        distressConfidence = 0.88f,
        keywordConfidence = 0.85f,
        combinedConfidence = 0.88f,
        matchedLabels = listOf("screaming_vocal_friction", "help")
    )

    override fun close() = Unit
}
