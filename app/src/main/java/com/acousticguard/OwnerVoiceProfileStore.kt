package com.acousticguard

import kotlin.math.sqrt

/** Bounded, non-audio statistics that can accompany an enrollment embedding. */
data class OwnerVoiceStatistics(
    val meanRms: Float = 0f,
    val meanZeroCrossingRate: Float = 0f,
    val meanHighFrequencyRatio: Float = 0f
) {
    init {
        require(meanRms.isFinite() && meanRms in 0f..1f) { "meanRms must be in [0, 1]" }
        require(meanZeroCrossingRate.isFinite() && meanZeroCrossingRate in 0f..1f) {
            "meanZeroCrossingRate must be in [0, 1]"
        }
        require(meanHighFrequencyRatio.isFinite() && meanHighFrequencyRatio in 0f..1f) {
            "meanHighFrequencyRatio must be in [0, 1]"
        }
    }
}

data class OwnerVoiceProfileStatus(
    val enrolled: Boolean,
    val sampleCount: Int,
    val embeddingDimension: Int?,
    val statistics: OwnerVoiceStatistics
)

/** Result of matching one embedding against the local profile. */
data class OwnerVoiceMatch(
    val enrolled: Boolean,
    val similarity: Float,
    val matched: Boolean,
    val threshold: Float = BoundedOwnerVoiceProfileStore.DEFAULT_SIMILARITY_THRESHOLD
) {
    val available: Boolean get() = enrolled
}

/**
 * Local owner-voice storage contract.
 *
 * Implementations receive embeddings and bounded statistics only. PCM samples
 * are deliberately not part of this API, so a profile cannot accidentally
 * become an audio recording store.
 */
interface OwnerVoiceProfileStore {
    fun addEnrollmentSample(
        embedding: FloatArray,
        statistics: OwnerVoiceStatistics = OwnerVoiceStatistics()
    ): Boolean

    fun enroll(
        embedding: FloatArray,
        statistics: OwnerVoiceStatistics = OwnerVoiceStatistics()
    ): Boolean = addEnrollmentSample(embedding, statistics)

    fun clear()

    fun status(): OwnerVoiceProfileStatus

    fun match(embedding: FloatArray): OwnerVoiceMatch
}

/**
 * In-process local profile store with hard memory bounds.
 *
 * The implementation keeps normalized embeddings, a maximum number of
 * enrollment samples, and a running average of three bounded statistics. It
 * never accepts PCM and returns defensive copies through no public method.
 */
class BoundedOwnerVoiceProfileStore(
    private val maxEnrollmentSamples: Int = DEFAULT_MAX_ENROLLMENT_SAMPLES,
    private val maxEmbeddingDimension: Int = DEFAULT_MAX_EMBEDDING_DIMENSION,
    private val similarityThreshold: Float = DEFAULT_SIMILARITY_THRESHOLD
) : OwnerVoiceProfileStore {
    private val samples = ArrayList<FloatArray>(maxEnrollmentSamples.coerceAtLeast(1))
    private var dimension: Int? = null
    private var accumulatedStatistics = OwnerVoiceStatistics()

    init {
        require(maxEnrollmentSamples in 1..MAX_ALLOWED_ENROLLMENT_SAMPLES) {
            "maxEnrollmentSamples must be between 1 and $MAX_ALLOWED_ENROLLMENT_SAMPLES"
        }
        require(maxEmbeddingDimension in 1..MAX_ALLOWED_EMBEDDING_DIMENSION) {
            "maxEmbeddingDimension must be between 1 and $MAX_ALLOWED_EMBEDDING_DIMENSION"
        }
        require(similarityThreshold in 0f..1f && similarityThreshold.isFinite()) {
            "similarityThreshold must be in [0, 1]"
        }
    }

    @Synchronized
    override fun addEnrollmentSample(
        embedding: FloatArray,
        statistics: OwnerVoiceStatistics
    ): Boolean {
        if (samples.size >= maxEnrollmentSamples) return false
        val normalized = normalizeAndValidate(embedding) ?: return false
        val expectedDimension = dimension
        if (expectedDimension != null && expectedDimension != normalized.size) return false
        dimension = normalized.size
        samples += normalized
        accumulatedStatistics = averageStatistics(accumulatedStatistics, statistics, samples.size)
        return true
    }

    /** Alias suitable for enrollment flows that call the operation `enroll`. */
    @Synchronized
    override fun enroll(
        embedding: FloatArray,
        statistics: OwnerVoiceStatistics
    ): Boolean = addEnrollmentSample(embedding, statistics)

    @Synchronized
    override fun clear() {
        samples.clear()
        dimension = null
        accumulatedStatistics = OwnerVoiceStatistics()
    }

    @Synchronized
    override fun status(): OwnerVoiceProfileStatus = OwnerVoiceProfileStatus(
        enrolled = samples.isNotEmpty(),
        sampleCount = samples.size,
        embeddingDimension = dimension,
        statistics = accumulatedStatistics
    )

    @Synchronized
    override fun match(embedding: FloatArray): OwnerVoiceMatch {
        val normalized = normalizeAndValidate(embedding)
        val currentDimension = dimension
        if (normalized == null || samples.isEmpty() || currentDimension != normalized.size) {
            return OwnerVoiceMatch(
                enrolled = samples.isNotEmpty(),
                similarity = 0f,
                matched = false,
                threshold = similarityThreshold
            )
        }

        // Average all enrollment similarities. This avoids letting one
        // accidental sample override the rest of the profile.
        var total = 0f
        samples.forEach { total += cosineSimilarity(it, normalized) }
        val similarity = (total / samples.size).coerceIn(0f, 1f)
        return OwnerVoiceMatch(
            enrolled = true,
            similarity = similarity,
            matched = similarity >= similarityThreshold,
            threshold = similarityThreshold
        )
    }

    private fun normalizeAndValidate(embedding: FloatArray): FloatArray? {
        if (embedding.isEmpty() || embedding.size > maxEmbeddingDimension) return null
        var normSquared = 0.0
        for (value in embedding) {
            if (!value.isFinite()) return null
            normSquared += value.toDouble() * value.toDouble()
        }
        if (!normSquared.isFinite() || normSquared <= 1.0e-12) return null
        val norm = sqrt(normSquared).toFloat()
        return FloatArray(embedding.size) { index -> embedding[index] / norm }
    }

    private fun averageStatistics(
        previous: OwnerVoiceStatistics,
        next: OwnerVoiceStatistics,
        count: Int
    ): OwnerVoiceStatistics {
        if (count == 1) return next
        val previousCount = count - 1
        return OwnerVoiceStatistics(
            meanRms = ((previous.meanRms * previousCount) + next.meanRms) / count,
            meanZeroCrossingRate = ((previous.meanZeroCrossingRate * previousCount) + next.meanZeroCrossingRate) / count,
            meanHighFrequencyRatio = ((previous.meanHighFrequencyRatio * previousCount) + next.meanHighFrequencyRatio) / count
        )
    }

    private fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
        var dot = 0f
        for (index in left.indices) dot += left[index] * right[index]
        return dot.coerceIn(-1f, 1f)
    }

    companion object {
        const val DEFAULT_MAX_ENROLLMENT_SAMPLES = 8
        const val DEFAULT_MAX_EMBEDDING_DIMENSION = 256
        const val DEFAULT_SIMILARITY_THRESHOLD = 0.82f
        const val MAX_ALLOWED_ENROLLMENT_SAMPLES = 32
        const val MAX_ALLOWED_EMBEDDING_DIMENSION = 1_024
    }
}

/** Explicit name for callers that want to document local, non-PCM storage. */
typealias LocalOwnerVoiceProfileStore = BoundedOwnerVoiceProfileStore
