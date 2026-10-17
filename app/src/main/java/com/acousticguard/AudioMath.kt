package com.acousticguard

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class AudioFeatures(
    val rms: Float,
    val zcr: Float,
    val peak: Float,
    val baselineRms: Float,
    val rmsThreshold: Float,
    val anomalous: Boolean
)

/** Low-cost gate. It intentionally does not allocate inside [analyze]. */
class TierOneGate(
    private val thresholdStore: AdaptiveThresholdStore,
    private val minimumRms: Float = 0.040f,
    private val zcrLow: Float = 0.030f,
    private val zcrHigh: Float = 0.55f
) {
    private var baselineRms = 0.025f

    fun analyze(frame: ShortArray): AudioFeatures {
        if (frame.isEmpty()) {
            return AudioFeatures(0f, 0f, 0f, baselineRms, minimumRms, false)
        }

        var sumSquares = 0.0
        var crossings = 0
        var peak = 0f
        var previous = frame[0].toFloat() / Short.MAX_VALUE
        for (i in frame.indices) {
            val normalized = frame[i].toFloat() / Short.MAX_VALUE
            sumSquares += normalized * normalized
            peak = max(peak, abs(normalized))
            if (i > 0 && ((normalized >= 0f) != (previous >= 0f))) crossings++
            previous = normalized
        }
        val rms = sqrt(sumSquares / frame.size).toFloat()
        val zcr = if (frame.size == 1) 0f else crossings.toFloat() / (frame.size - 1)
        val threshold = max(
            minimumRms,
            baselineRms * thresholdStore.rmsMultiplier()
        )
        val isLoudVocalSurge = (peak >= 0.28f && rms >= 0.028f) || rms >= threshold
        val anomalous = isLoudVocalSurge && zcr in zcrLow..zcrHigh

        // Baseline follows ordinary ambient audio but freezes during a candidate spike.
        if (!anomalous) baselineRms = baselineRms * 0.96f + rms * 0.04f
        return AudioFeatures(rms, zcr, peak, baselineRms, threshold, anomalous)
    }
}

/** Linear PCM16 resampling without a third-party DSP dependency. */
object PcmResampler {
    fun to16Khz(input: ShortArray, sourceRate: Int): ShortArray {
        require(sourceRate > 0)
        if (sourceRate == 16_000 || input.isEmpty()) return input.copyOf()
        val outputSize = max(1, floor(input.size * 16_000.0 / sourceRate).toInt())
        val output = ShortArray(outputSize)
        val ratio = sourceRate.toDouble() / 16_000.0
        for (i in output.indices) {
            val sourcePosition = i * ratio
            val left = min(input.size - 1, sourcePosition.toInt())
            val right = min(input.size - 1, left + 1)
            val fraction = sourcePosition - left
            output[i] = (input[left] + (input[right] - input[left]) * fraction).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return output
    }
}
