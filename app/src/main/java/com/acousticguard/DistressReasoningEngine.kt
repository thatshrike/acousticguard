package com.acousticguard

/** Input to the pure reasoning layer. No Android or audio storage is needed. */
data class DistressReasoningInput(
    val modelScores: ModelScores?,
    val tierOne: TierOneFeatures,
    val ownerVoice: OwnerVoiceMatch? = null
)

enum class DistressAction {
    NO_ACTION,
    MONITOR,
    PRE_ALERT
}

/** The protected path that won precedence, if any. */
enum class DistressReasoningPath {
    NONE,
    BENIGN_SPEECH_OWNER_SUPPRESSED,
    BENIGN_SPEECH,
    TIER_ONE_CANDIDATE,
    STRONG_NON_VERBAL_DISTRESS,
    IMPACT_WITH_MOTION,
    EXPLICIT_DISTRESS,
    DURESS
}

enum class OwnerVoiceDecision {
    NOT_EVALUATED,
    PROFILE_UNAVAILABLE,
    NOT_MATCHED,
    MATCHED
}

data class DistressReasoningResult(
    val action: DistressAction,
    val path: DistressReasoningPath,
    val events: Set<DistressEvent>,
    val confidence: Float,
    val ownerVoiceDecision: OwnerVoiceDecision,
    val ownerVoiceSuppressed: Boolean
) {
    val shouldPreAlert: Boolean get() = action == DistressAction.PRE_ALERT
    val shouldEscalate: Boolean get() = shouldPreAlert
    val suppressionAllowed: Boolean
        get() = ownerVoiceSuppressed && path == DistressReasoningPath.BENIGN_SPEECH_OWNER_SUPPRESSED
}

/**
 * Conservative model-side policy. The service can call [decide] and translate
 * the result into its existing notification/dispatch flow later.
 *
 * Precedence is explicit: duress, explicit distress, impact with concurrent
 * motion, and strong non-verbal distress are protected paths. Owner voice is
 * considered only after those paths have been ruled out and only when the
 * model supplies benign-speech evidence.
 */
class DistressReasoningEngine(
    private val benignSpeechThreshold: Float = DEFAULT_BENIGN_SPEECH_THRESHOLD,
    private val ownerVoiceThreshold: Float = BoundedOwnerVoiceProfileStore.DEFAULT_SIMILARITY_THRESHOLD
) {
    init {
        require(benignSpeechThreshold in 0f..1f && benignSpeechThreshold.isFinite()) {
            "benignSpeechThreshold must be in [0, 1]"
        }
        require(ownerVoiceThreshold in 0f..1f && ownerVoiceThreshold.isFinite()) {
            "ownerVoiceThreshold must be in [0, 1]"
        }
    }

    fun decide(input: DistressReasoningInput): DistressReasoningResult =
        decide(input.modelScores, input.tierOne, input.ownerVoice)

    fun decide(
        scores: ModelScores?,
        tierOne: TierOneFeatures,
        ownerVoice: OwnerVoiceMatch? = null
    ): DistressReasoningResult {
        val evidence = collectEvidence(scores)
        val protectedEvents = evidence.events
        val ownerDecision = ownerDecision(ownerVoice)
        val impactWithMotion = hasImpactWithMotion(tierOne, evidence)
        val explicitDistress = evidence.score(DistressEvent.EXPLICIT_DISTRESS) >= EXPLICIT_DISTRESS_THRESHOLD
        val duress = evidence.score(DistressEvent.DURESS) >= DURESS_THRESHOLD
        val strongNonVerbal = hasStrongNonVerbal(scores, evidence)

        // Protected distress paths always win over owner voice.
        when {
            duress -> return result(
                action = DistressAction.PRE_ALERT,
                path = DistressReasoningPath.DURESS,
                events = protectedEvents,
                confidence = evidence.score(DistressEvent.DURESS),
                ownerDecision = ownerDecision
            )

            explicitDistress -> return result(
                action = DistressAction.PRE_ALERT,
                path = DistressReasoningPath.EXPLICIT_DISTRESS,
                events = protectedEvents,
                confidence = evidence.score(DistressEvent.EXPLICIT_DISTRESS),
                ownerDecision = ownerDecision
            )

            impactWithMotion -> return result(
                action = DistressAction.PRE_ALERT,
                path = DistressReasoningPath.IMPACT_WITH_MOTION,
                events = protectedEvents + DistressEvent.IMPACT,
                confidence = maxOf(
                    evidence.score(DistressEvent.IMPACT),
                    scores?.distressConfidence.orZero(),
                    tierOne.motionShockScore
                ).coerceIn(0f, 1f),
                ownerDecision = ownerDecision
            )

            strongNonVerbal -> return result(
                action = DistressAction.PRE_ALERT,
                path = DistressReasoningPath.STRONG_NON_VERBAL_DISTRESS,
                events = protectedEvents + DistressEvent.STRONG_NON_VERBAL_DISTRESS,
                confidence = maxOf(
                    evidence.score(DistressEvent.STRONG_NON_VERBAL_DISTRESS),
                    scores?.distressConfidence.orZero()
                ).coerceIn(0f, 1f),
                ownerDecision = ownerDecision
            )
        }

        val benignSpeech = evidence.score(DistressEvent.BENIGN_SPEECH) >= benignSpeechThreshold
        val ownerMatched = ownerVoice?.enrolled == true && ownerVoice.matched &&
            ownerVoice.similarity.isFinite() &&
            ownerVoice.threshold.isFinite() &&
            ownerVoice.similarity >= maxOf(ownerVoiceThreshold, ownerVoice.threshold)
        if (benignSpeech && ownerMatched) {
            return result(
                action = DistressAction.NO_ACTION,
                path = DistressReasoningPath.BENIGN_SPEECH_OWNER_SUPPRESSED,
                events = protectedEvents + DistressEvent.BENIGN_SPEECH,
                confidence = evidence.score(DistressEvent.BENIGN_SPEECH),
                ownerDecision = OwnerVoiceDecision.MATCHED,
                ownerVoiceSuppressed = true
            )
        }

        if (benignSpeech) {
            return result(
                action = DistressAction.MONITOR,
                path = DistressReasoningPath.BENIGN_SPEECH,
                events = protectedEvents + DistressEvent.BENIGN_SPEECH,
                confidence = evidence.score(DistressEvent.BENIGN_SPEECH),
                ownerDecision = ownerDecision
            )
        }

        if (tierOne.candidate || scores?.combinedConfidence.orZero() >= MONITOR_SCORE_THRESHOLD) {
            return result(
                action = DistressAction.MONITOR,
                path = DistressReasoningPath.TIER_ONE_CANDIDATE,
                events = protectedEvents,
                confidence = maxOf(scores?.combinedConfidence.orZero(), tierOne.sensorFusionBoost).coerceIn(0f, 1f),
                ownerDecision = ownerDecision
            )
        }

        return result(
            action = DistressAction.NO_ACTION,
            path = DistressReasoningPath.NONE,
            events = protectedEvents,
            confidence = scores?.combinedConfidence.orZero().coerceIn(0f, 1f),
            ownerDecision = ownerDecision
        )
    }

    private fun collectEvidence(scores: ModelScores?): Evidence {
        if (scores == null) return Evidence(emptyMap(), emptySet())
        val eventScores = mutableMapOf<DistressEvent, Float>()
        val events = mutableSetOf<DistressEvent>()

        fun add(event: DistressEvent, score: Float) {
            if (!score.isFinite()) return
            val clamped = score.coerceIn(0f, 1f)
            eventScores[event] = maxOf(eventScores[event] ?: 0f, clamped)
            if (clamped >= LABEL_EVIDENCE_THRESHOLD) events += event
        }

        scores.detectedEvents.forEach { event -> add(event, scores.combinedConfidence.orZero()) }
        scores.labelScores.forEach { labelScore ->
            val normalized = DistressLabelTaxonomy.normalize(labelScore.label) ?: return@forEach
            add(normalized.event, labelScore.score)
        }
        // Older adapters expose only matchedLabels. They are still exact data
        // values, never substrings, and inherit the relevant calibrated score.
        scores.matchedLabels.forEach { rawLabel ->
            val normalized = DistressLabelTaxonomy.normalize(rawLabel) ?: return@forEach
            val score = when (normalized.event) {
                DistressEvent.EXPLICIT_DISTRESS -> scores.keywordConfidence
                DistressEvent.BENIGN_SPEECH -> scores.benignSpeechConfidence
                else -> scores.distressConfidence
            }
            add(normalized.event, score)
        }
        if (scores.explicitHelpKeyword && scores.keywordConfidence >= RapidEscalationPolicy.RAPID_KEYWORD_THRESHOLD) {
            add(DistressEvent.EXPLICIT_DISTRESS, scores.keywordConfidence)
        }
        if (scores.strongDistressLabel && scores.distressConfidence >= RapidEscalationPolicy.STRONG_DISTRESS_THRESHOLD) {
            add(DistressEvent.STRONG_NON_VERBAL_DISTRESS, scores.distressConfidence)
        }
        if (scores.benignSpeechConfidence.isFinite()) add(DistressEvent.BENIGN_SPEECH, scores.benignSpeechConfidence)
        return Evidence(eventScores, events)
    }

    private fun hasImpactWithMotion(features: TierOneFeatures, evidence: Evidence): Boolean {
        if (!features.impact.detected || !features.motionConcurrent) return false
        if (features.accelerationPeak < RapidEscalationPolicy.MIN_ACCELERATION_PEAK ||
            features.gyroscopePeak < RapidEscalationPolicy.MIN_GYROSCOPE_PEAK
        ) return false
        // Motion cannot create a distress event by itself. It must corroborate
        // typed impact/distress evidence or a strong calibrated distress score.
        return evidence.score(DistressEvent.IMPACT) >= LABEL_EVIDENCE_THRESHOLD ||
            evidence.score(DistressEvent.STRONG_NON_VERBAL_DISTRESS) >= RapidEscalationPolicy.STRONG_DISTRESS_THRESHOLD
    }

    private fun hasStrongNonVerbal(scores: ModelScores?, evidence: Evidence): Boolean {
        if (evidence.score(DistressEvent.STRONG_NON_VERBAL_DISTRESS) < RapidEscalationPolicy.STRONG_DISTRESS_THRESHOLD) {
            return false
        }
        return scores?.distressConfidence.orZero() >= RapidEscalationPolicy.SINGLE_WINDOW_DISTRESS_THRESHOLD ||
            scores?.strongDistressLabel == true
    }

    private fun ownerDecision(ownerVoice: OwnerVoiceMatch?): OwnerVoiceDecision {
        if (ownerVoice == null) return OwnerVoiceDecision.NOT_EVALUATED
        if (!ownerVoice.enrolled) return OwnerVoiceDecision.PROFILE_UNAVAILABLE
        return if (
            ownerVoice.matched &&
                ownerVoice.similarity.isFinite() &&
                ownerVoice.threshold.isFinite() &&
                ownerVoice.similarity >= maxOf(ownerVoiceThreshold, ownerVoice.threshold)
        ) OwnerVoiceDecision.MATCHED else OwnerVoiceDecision.NOT_MATCHED
    }

    private fun result(
        action: DistressAction,
        path: DistressReasoningPath,
        events: Set<DistressEvent>,
        confidence: Float,
        ownerDecision: OwnerVoiceDecision,
        ownerVoiceSuppressed: Boolean = false
    ) = DistressReasoningResult(
        action = action,
        path = path,
        events = events,
        confidence = confidence.coerceIn(0f, 1f),
        ownerVoiceDecision = ownerDecision,
        ownerVoiceSuppressed = ownerVoiceSuppressed
    )

    private data class Evidence(
        val scores: Map<DistressEvent, Float>,
        val events: Set<DistressEvent>
    ) {
        fun score(event: DistressEvent): Float = scores[event] ?: 0f
    }

    private fun Float?.orZero(): Float = if (this?.isFinite() == true) this else 0f

    companion object {
        const val DEFAULT_BENIGN_SPEECH_THRESHOLD = 0.75f
        const val LABEL_EVIDENCE_THRESHOLD = 0.82f
        const val EXPLICIT_DISTRESS_THRESHOLD = RapidEscalationPolicy.RAPID_KEYWORD_THRESHOLD
        const val DURESS_THRESHOLD = LABEL_EVIDENCE_THRESHOLD
        const val MONITOR_SCORE_THRESHOLD = 0.50f
    }
}
