package com.acousticguard

import android.content.Context
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Stores three scalar feature profiles; no calibration PCM is persisted. */
class PersonalizationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE
    )

    fun sampleCount(): Int = preferences.getInt(KEY_COUNT, 0).coerceIn(0, MAX_SAMPLES)

    fun recordSafeSample(features: TierOneFeatures): Int {
        val count = sampleCount()
        if (count >= MAX_SAMPLES) return count
        val prefix = "sample_$count"
        preferences.edit()
            .putFloat("$prefix.rms", features.audio.rms)
            .putFloat("$prefix.zcr", features.audio.zcr)
            .putFloat("$prefix.hf", features.spectral.highFrequencyRatio ?: 0f)
            .putFloat("$prefix.peak", features.audio.peak)
            .putInt(KEY_COUNT, count + 1)
            .apply()
        return count + 1
    }

    /**
     * Applies a small, bounded bias away from a fully trained user's safe
     * acoustic signature. This is not weight training and never mutates the TFLite
     * model. Three samples are required before it has any effect.
     */
    fun adjust(scores: ModelScores, features: TierOneFeatures): ModelScores {
        val count = sampleCount()
        if (count < MAX_SAMPLES) return scores
        var nearestDistance = Float.POSITIVE_INFINITY
        for (index in 0 until MAX_SAMPLES) {
            val prefix = "sample_$index"
            val distance = normalizedDistance(
                features.audio.rms,
                preferences.getFloat("$prefix.rms", 0f),
                0.25f
            ) + normalizedDistance(
                features.audio.zcr,
                preferences.getFloat("$prefix.zcr", 0f),
                0.25f
            ) + normalizedDistance(
                features.spectral.highFrequencyRatio ?: 0f,
                preferences.getFloat("$prefix.hf", 0f),
                0.5f
            ) + normalizedDistance(
                features.audio.peak,
                preferences.getFloat("$prefix.peak", 0f),
                0.5f
            )
            nearestDistance = min(nearestDistance, distance)
        }
        if (nearestDistance > SAFE_MATCH_DISTANCE) return scores
        val reduction = (SAFE_BIAS_REDUCTION * (1f - nearestDistance / SAFE_MATCH_DISTANCE))
            .coerceIn(0f, SAFE_BIAS_REDUCTION)
        return scores.copy(
            distressConfidence = (scores.distressConfidence - reduction).coerceAtLeast(0f),
            keywordConfidence = (scores.keywordConfidence - reduction).coerceAtLeast(0f),
            combinedConfidence = (scores.combinedConfidence - reduction).coerceAtLeast(0f)
        )
    }

    fun clear() = preferences.edit().clear().apply()

    private fun normalizedDistance(value: Float, reference: Float, scale: Float): Float =
        abs(value - reference) / max(scale, 1.0e-4f)

    companion object {
        private const val PREFERENCES = "acousticguard_personalization"
        private const val KEY_COUNT = "sample_count"
        private const val MAX_SAMPLES = 3
        private const val SAFE_MATCH_DISTANCE = 2.5f
        private const val SAFE_BIAS_REDUCTION = 0.12f
    }
}
