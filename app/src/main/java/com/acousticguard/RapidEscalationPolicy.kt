package com.acousticguard

enum class RapidEscalationReason {
    EXPLICIT_HELP_KEYWORD,
    STRONG_VOCAL_DISTRESS,
    STRONG_IMPACT_WITH_MOTION
}

data class RapidEscalationDecision(
    val shouldPreAlert: Boolean,
    val reason: RapidEscalationReason? = null
)

/**
 * One-window exceptions to the ordinary two-window policy. The exception is
 * intentionally narrow: model semantics are still required, and sensor data
 * can only corroborate acoustic evidence.
 */
object RapidEscalationPolicy {
    const val RAPID_KEYWORD_THRESHOLD = 0.90f
    const val STRONG_DISTRESS_THRESHOLD = 0.90f
    const val SINGLE_WINDOW_DISTRESS_THRESHOLD = 0.95f
    const val ADJUSTED_SCORE_FLOOR = 0.82f
    const val MIN_ACCELERATION_PEAK = 0.75f
    const val MIN_GYROSCOPE_PEAK = 0.65f

    fun decide(features: TierOneFeatures, raw: ModelScores, adjusted: ModelScores): RapidEscalationDecision {
        val voiceLike = features.reasons.any {
            it == TierOneReason.RMS_ZCR ||
                it == TierOneReason.HIGH_FREQUENCY ||
                it == TierOneReason.RESPIRATORY_PERIODICITY
        }
        if (
            features.candidate &&
            voiceLike &&
            raw.explicitHelpKeyword &&
            raw.keywordConfidence >= RAPID_KEYWORD_THRESHOLD &&
            adjusted.combinedConfidence >= ADJUSTED_SCORE_FLOOR
        ) {
            return RapidEscalationDecision(true, RapidEscalationReason.EXPLICIT_HELP_KEYWORD)
        }

        if (
            features.candidate &&
            voiceLike &&
            raw.strongDistressLabel &&
            raw.distressConfidence >= SINGLE_WINDOW_DISTRESS_THRESHOLD &&
            adjusted.combinedConfidence >= SINGLE_WINDOW_DISTRESS_THRESHOLD
        ) {
            return RapidEscalationDecision(true, RapidEscalationReason.STRONG_VOCAL_DISTRESS)
        }

        val strongImpactWithMotion =
            features.impact.detected &&
                features.motionConcurrent &&
                features.accelerationPeak >= MIN_ACCELERATION_PEAK &&
                features.gyroscopePeak >= MIN_GYROSCOPE_PEAK &&
                raw.strongDistressLabel &&
                raw.distressConfidence >= STRONG_DISTRESS_THRESHOLD &&
                adjusted.combinedConfidence >= ADJUSTED_SCORE_FLOOR
        if (strongImpactWithMotion) {
            return RapidEscalationDecision(true, RapidEscalationReason.STRONG_IMPACT_WITH_MOTION)
        }
        return RapidEscalationDecision(false)
    }
}
