package com.acousticguard

import kotlin.math.max

data class AmbientNoiseSnapshot(
    val rms: Float,
    val highFrequencyRatio: Float,
    val rmsCandidateFloor: Float,
    val highFrequencyCandidateFloor: Float
)

/**
 * Slow, fail-safe environment profile. It learns only from frames that were
 * not candidates, so a distress spike cannot immediately raise its own gate.
 */
class AdaptiveNoiseProfile(
    private val minimumRmsFloor: Float = 0.02f,
    private val minimumHighFrequencyFloor: Float = 0.35f
) {
    private var rmsBaseline = 0.025f
    private var highFrequencyBaseline = 0.15f

    fun snapshot(): AmbientNoiseSnapshot = AmbientNoiseSnapshot(
        rms = rmsBaseline,
        highFrequencyRatio = highFrequencyBaseline,
        rmsCandidateFloor = max(minimumRmsFloor, rmsBaseline * 2.0f),
        highFrequencyCandidateFloor = max(minimumHighFrequencyFloor, highFrequencyBaseline + 0.12f)
    )

    fun observe(rms: Float, highFrequencyRatio: Float?, candidate: Boolean) {
        if (candidate || !rms.isFinite() || rms <= 0f) return
        rmsBaseline = rmsBaseline * 0.985f + rms.coerceAtMost(1f) * 0.015f
        if (highFrequencyRatio != null && highFrequencyRatio.isFinite()) {
            highFrequencyBaseline = highFrequencyBaseline * 0.98f + highFrequencyRatio.coerceIn(0f, 1f) * 0.02f
        }
    }

    fun reset() {
        rmsBaseline = 0.025f
        highFrequencyBaseline = 0.15f
    }
}
