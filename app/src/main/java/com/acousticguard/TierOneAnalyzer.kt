package com.acousticguard

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class TierOneReason {
    RMS_ZCR,
    HIGH_FREQUENCY,
    RESPIRATORY_PERIODICITY,
    IMPACT_TRANSIENT
}

data class SpectralFeatures(
    val highFrequencyRatio: Float?,
    val valid: Boolean,
    val totalPower: Float
)

data class RespiratoryFeatures(
    val periodicity: Float?,
    val periodMs: Long?,
    val modulationDepth: Float,
    val valid: Boolean
)

data class ImpactFeatures(
    val detected: Boolean,
    val peakRms: Float,
    val peakToBaseline: Float,
    val crestFactor: Float,
    val attackMs: Float?,
    val durationMs: Float?,
    val clipped: Boolean
)

data class TierOneFeatures(
    val audio: AudioFeatures,
    val spectral: SpectralFeatures,
    val respiratory: RespiratoryFeatures,
    val impact: ImpactFeatures,
    val candidate: Boolean,
    val reasons: Set<TierOneReason>
) {
    val fastTrack: Boolean
        get() = spectral.valid && respiratory.valid &&
            (spectral.highFrequencyRatio ?: 0f) >= 0.35f &&
            (respiratory.periodicity ?: 0f) >= 0.55f
}

class TierOneAnalyzer(
    private val gate: TierOneGate,
    private val spectral: SpectralAnalyzer = SpectralAnalyzer(),
    private val respiratory: RespiratoryPeriodicityDetector = RespiratoryPeriodicityDetector(),
    private val impact: ImpactTransientDetector = ImpactTransientDetector()
) {
    fun analyze(frame: ShortArray, sampleRate: Int = 16_000): TierOneFeatures {
        require(sampleRate > 0) { "sampleRate must be positive" }
        val audio = gate.analyze(frame)
        val spectralFeatures = spectral.analyze(frame, sampleRate)
        val respiratoryFeatures = respiratory.update(frame, sampleRate)
        val impactFeatures = impact.analyze(frame, sampleRate, audio.baselineRms)

        val reasons = buildSet {
            if (audio.anomalous) add(TierOneReason.RMS_ZCR)
            if (spectralFeatures.valid && (spectralFeatures.highFrequencyRatio ?: 0f) >= 0.35f && audio.rms >= 0.02f) {
                add(TierOneReason.HIGH_FREQUENCY)
            }
            if (respiratoryFeatures.valid && (respiratoryFeatures.periodicity ?: 0f) >= 0.55f) {
                add(TierOneReason.RESPIRATORY_PERIODICITY)
            }
            if (impactFeatures.detected) add(TierOneReason.IMPACT_TRANSIENT)
        }
        return TierOneFeatures(audio, spectralFeatures, respiratoryFeatures, impactFeatures, reasons.isNotEmpty(), reasons)
    }

    fun reset() {
        respiratory.reset()
        impact.reset()
    }
}

class SpectralAnalyzer(
    private val fftSize: Int = 512,
    private val hopSize: Int = 256,
    private val lowBandHz: Float = 80f,
    private val highBandStartHz: Float = 3_500f
) {
    private val real = FloatArray(fftSize)
    private val imaginary = FloatArray(fftSize)
    private val window = FloatArray(fftSize) { index ->
        (0.5 - 0.5 * cos(2.0 * Math.PI * index / fftSize)).toFloat()
    }

    init {
        require(fftSize >= 2 && fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two" }
        require(hopSize in 1..fftSize) { "hopSize must be within fftSize" }
        require(lowBandHz >= 0f && highBandStartHz > lowBandHz)
    }

    @Synchronized
    fun analyze(frame: ShortArray, sampleRate: Int): SpectralFeatures {
        require(sampleRate > 0) { "sampleRate must be positive" }
        if (frame.size < fftSize) return SpectralFeatures(null, false, 0f)
        val nyquist = sampleRate / 2f
        if (highBandStartHz >= nyquist) return SpectralFeatures(null, false, 0f)
        val lowBin = max(1, (lowBandHz * fftSize / sampleRate).toInt())
        val highBin = min(fftSize / 2, (highBandStartHz * fftSize / sampleRate).toInt())
        if (highBin >= fftSize / 2) return SpectralFeatures(null, false, 0f)

        var totalPower = 0.0
        var highPower = 0.0
        var start = 0
        var windows = 0
        while (start + fftSize <= frame.size) {
            var mean = 0.0
            for (i in 0 until fftSize) mean += frame[start + i] / 32768.0
            mean /= fftSize
            for (i in 0 until fftSize) {
                real[i] = ((frame[start + i] / 32768.0) - mean).toFloat() * window[i]
                imaginary[i] = 0f
            }
            fft()
            for (bin in lowBin until fftSize / 2) {
                val power = real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
                totalPower += power
                if (bin >= highBin) highPower += power
            }
            windows++
            start += hopSize
        }
        if (windows == 0 || !totalPower.isFinite() || totalPower <= 1.0e-8) {
            return SpectralFeatures(null, false, 0f)
        }
        val ratio = (highPower / totalPower).toFloat()
        return if (ratio.isFinite()) SpectralFeatures(ratio, true, totalPower.toFloat())
        else SpectralFeatures(null, false, 0f)
    }

    private fun fft() {
        var j = 0
        for (i in 1 until fftSize) {
            var bit = fftSize shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val realTemp = real[i]
                real[i] = real[j]
                real[j] = realTemp
                val imaginaryTemp = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = imaginaryTemp
            }
        }
        var length = 2
        while (length <= fftSize) {
            val angle = (-2.0 * Math.PI / length).toFloat()
            val wLengthReal = cos(angle.toDouble()).toFloat()
            val wLengthImaginary = sin(angle.toDouble()).toFloat()
            var offset = 0
            while (offset < fftSize) {
                var wReal = 1f
                var wImaginary = 0f
                for (i in 0 until length / 2) {
                    val even = offset + i
                    val odd = even + length / 2
                    val oddReal = real[odd] * wReal - imaginary[odd] * wImaginary
                    val oddImaginary = real[odd] * wImaginary + imaginary[odd] * wReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextWReal = wReal * wLengthReal - wImaginary * wLengthImaginary
                    wImaginary = wReal * wLengthImaginary + wImaginary * wLengthReal
                    wReal = nextWReal
                }
                offset += length
            }
            length = length shl 1
        }
    }
}

class RespiratoryPeriodicityDetector(
    private val envelopeRateHz: Float = 10f,
    private val historySeconds: Int = 16,
    private val minPeriodMs: Long = 2_000L,
    private val maxPeriodMs: Long = 8_000L
) {
    private val maxSamples = (envelopeRateHz * historySeconds).toInt()
    private val history = FloatArray(maxSamples)
    private var historyCount = 0
    private var historyWriteIndex = 0

    init {
        require(envelopeRateHz > 0f && historySeconds > 0)
        require(minPeriodMs > 0 && maxPeriodMs >= minPeriodMs)
    }

    @Synchronized
    fun update(frame: ShortArray, sampleRate: Int): RespiratoryFeatures {
        require(sampleRate > 0) { "sampleRate must be positive" }
        val envelopeWindow = max(1, sampleRate / 50) // 20 ms
        val envelopeHop = max(1, sampleRate / envelopeRateHz.toInt())
        if (frame.isEmpty() || frame.size < envelopeWindow) {
            reset()
            return emptyResult()
        }
        var start = 0
        var added = 0
        while (start + envelopeWindow <= frame.size) {
            var sum = 0.0
            for (i in 0 until envelopeWindow) {
                val sample = frame[start + i] / 32768.0
                sum += sample * sample
            }
            val envelope = sqrt(sum / envelopeWindow).toFloat()
            if (!envelope.isFinite() || envelope <= 0.0001f) {
                reset()
                return emptyResult()
            }
            append(envelope)
            added++
            start += envelopeHop
        }
        if (added == 0) {
            reset()
            return emptyResult()
        }
        return estimate()
    }

    @Synchronized
    fun reset() {
        history.fill(0f)
        historyCount = 0
        historyWriteIndex = 0
    }

    private fun append(value: Float) {
        history[historyWriteIndex] = value
        historyWriteIndex = (historyWriteIndex + 1) % maxSamples
        historyCount = min(maxSamples, historyCount + 1)
    }

    private fun estimate(): RespiratoryFeatures {
        if (historyCount < 2) return emptyResult()
        val values = FloatArray(historyCount)
        val start = (historyWriteIndex - historyCount + maxSamples) % maxSamples
        var mean = 0.0
        for (i in values.indices) {
            values[i] = history[(start + i) % maxSamples]
            mean += values[i]
        }
        mean /= values.size
        var variance = 0.0
        for (value in values) variance += (value - mean) * (value - mean)
        val modulationDepth = (sqrt(variance / values.size) / mean).toFloat()
        if (!modulationDepth.isFinite() || modulationDepth < 0.08f) {
            return RespiratoryFeatures(null, null, modulationDepth.coerceAtLeast(0f), false)
        }

        val minLag = max(1, (minPeriodMs * envelopeRateHz / 1_000f).toInt())
        val maxLag = min(values.size / 2, (maxPeriodMs * envelopeRateHz / 1_000f).toInt())
        if (maxLag < minLag || values.size < minLag * 2) return RespiratoryFeatures(null, null, modulationDepth, false)

        var bestScore = -1f
        var bestLag = 0
        for (lag in minLag..maxLag) {
            var numerator = 0.0
            var leftPower = 0.0
            var rightPower = 0.0
            for (i in 0 until values.size - lag) {
                val left = values[i] - mean
                val right = values[i + lag] - mean
                numerator += left * right
                leftPower += left * left
                rightPower += right * right
            }
            val denominator = sqrt(leftPower * rightPower)
            if (denominator > 0.0) {
                val score = (numerator / denominator).toFloat()
                if (score.isFinite() && score > bestScore) {
                    bestScore = score
                    bestLag = lag
                }
            }
        }
        if (bestLag == 0 || bestScore < 0.55f) return RespiratoryFeatures(bestScore.coerceAtLeast(0f), null, modulationDepth, false)
        return RespiratoryFeatures(
            periodicity = bestScore,
            periodMs = (bestLag * 1_000f / envelopeRateHz).toLong(),
            modulationDepth = modulationDepth,
            valid = true
        )
    }

    private fun emptyResult() = RespiratoryFeatures(null, null, 0f, false)
}

class ImpactTransientDetector(
    private val windowSamples: Int = 160,
    private val hopSamples: Int = 80,
    private val maxDurationMs: Float = 120f
) {
    private var previousTail = ShortArray(0)
    private var cooldownRemainingMs = 0f

    init {
        require(windowSamples > 0 && hopSamples > 0)
    }

    @Synchronized
    fun analyze(frame: ShortArray, sampleRate: Int, baselineRms: Float): ImpactFeatures {
        require(sampleRate > 0) { "sampleRate must be positive" }
        if (frame.isEmpty()) return emptyResult()
        val combined = ShortArray(previousTail.size + frame.size)
        previousTail.copyInto(combined)
        frame.copyInto(combined, previousTail.size)
        val tailSize = min(windowSamples, combined.size)
        previousTail = combined.copyOfRange(combined.size - tailSize, combined.size)
        cooldownRemainingMs = max(0f, cooldownRemainingMs - combined.size * 1_000f / sampleRate)

        val windowCount = if (combined.size < windowSamples) 0 else 1 + (combined.size - windowSamples) / hopSamples
        if (windowCount == 0) return emptyResult()
        val rmsWindows = FloatArray(windowCount)
        var peakWindow = 0
        var peakRms = 0f
        var clippedCount = 0
        var sampleCount = 0
        var frameSum = 0.0
        for (sample in combined) {
            frameSum += sample / 32768.0
            if (abs(sample.toInt()) >= 32_600) clippedCount++
            sampleCount++
        }
        val mean = frameSum / sampleCount
        var sumSquares = 0.0
        for (sample in combined) {
            val normalized = sample / 32768.0 - mean
            sumSquares += normalized * normalized
        }
        val frameRms = sqrt(sumSquares / sampleCount).toFloat()
        for (window in 0 until windowCount) {
            val start = window * hopSamples
            var sum = 0.0
            for (i in 0 until windowSamples) {
                val normalized = combined[start + i] / 32768.0 - mean
                sum += normalized * normalized
            }
            rmsWindows[window] = sqrt(sum / windowSamples).toFloat()
            if (rmsWindows[window] > peakRms) {
                peakRms = rmsWindows[window]
                peakWindow = window
            }
        }
        val sorted = rmsWindows.copyOf().apply { sort() }
        val localBaseline = max(0.005f, sorted[sorted.size / 2])
        val peakToBaseline = peakRms / localBaseline
        val crestFactor = peakRms / max(0.001f, frameRms)
        val attackThreshold = peakRms * 0.20f
        var attackWindow = peakWindow
        while (attackWindow > 0 && rmsWindows[attackWindow - 1] < attackThreshold) attackWindow--
        val attackMs = ((peakWindow - attackWindow) * hopSamples * 1_000f / sampleRate)
        val durationThreshold = peakRms * 0.50f
        var firstAbove = peakWindow
        while (firstAbove > 0 && rmsWindows[firstAbove - 1] >= durationThreshold) firstAbove--
        var lastAbove = peakWindow
        while (lastAbove + 1 < rmsWindows.size && rmsWindows[lastAbove + 1] >= durationThreshold) lastAbove++
        val durationMs = ((lastAbove - firstAbove + 1) * hopSamples * 1_000f / sampleRate)
        val clipped = clippedCount.toFloat() / sampleCount >= 0.01f
        val detected = cooldownRemainingMs <= 0f && !clipped &&
            peakRms >= max(0.06f, baselineRms * 4f) && peakToBaseline >= 4f &&
            attackMs <= 20f && durationMs in 10f..maxDurationMs && crestFactor >= 3f
        if (detected) cooldownRemainingMs = 300f
        return ImpactFeatures(detected, peakRms, peakToBaseline, crestFactor, attackMs, durationMs, clipped)
    }

    @Synchronized
    fun reset() {
        previousTail = ShortArray(0)
        cooldownRemainingMs = 0f
    }

    private fun emptyResult() = ImpactFeatures(false, 0f, 0f, 0f, null, null, false)
}
