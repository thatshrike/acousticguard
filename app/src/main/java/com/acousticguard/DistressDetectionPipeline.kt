package com.acousticguard

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max

data class ModelScores(
    val distressConfidence: Float,
    val keywordConfidence: Float,
    val combinedConfidence: Float,
    val matchedLabels: List<String>,
    /** Set only by an exact canonical help label at the rapid-probe threshold. */
    val explicitHelpKeyword: Boolean = false,
    /** Set only by an exact canonical distress label at the strong-event threshold. */
    val strongDistressLabel: Boolean = false
)

data class PipelineResult(
    val scores: ModelScores?,
    val consecutiveQualifyingWindows: Int,
    val validated: Boolean,
    val modelError: String? = null,
    val tierOneReasons: Set<TierOneReason> = emptySet(),
    val fastTrack: Boolean = false,
    val rawScores: ModelScores? = null,
    val rapidReason: RapidEscalationReason? = null
)

interface DistressModel : Closeable {
    fun infer(pcm16: ShortArray): ModelScores
}

/**
 * TFLite adapter for the checked-in model contract:
 * - input tensor: FLOAT32, INT8, or UINT8 mono PCM
 * - output tensor 0: FLOAT32, INT8, or UINT8 calibrated scores
 * - assets/distress_multilingual_labels.txt: one label per output index
 *
 * A YAMNet classifier by itself does not recognize spoken words. The model used
 * here must be a calibrated audio event/keyword model whose labels include the
 * distress classes and multilingual help phrases listed in the README.
 */
class TfliteDistressModel(context: Context) : DistressModel {
    private val interpreter: Interpreter
    private val labels: List<String>
    private val inputElements: Int
    private val outputElements: Int
    private val inputType: DataType
    private val outputType: DataType
    private val inputScale: Float
    private val inputZeroPoint: Int
    private val outputScale: Float
    private val outputZeroPoint: Int

    init {
        val model = context.assets.open("distress_multilingual.tflite").use { stream ->
            val bytes = stream.readBytes()
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
        }
        interpreter = createInterpreter(model)
        val inputTensor = interpreter.getInputTensor(0)
        val outputTensor = interpreter.getOutputTensor(0)
        inputType = inputTensor.dataType()
        outputType = outputTensor.dataType()
        check(inputType == DataType.FLOAT32 || inputType == DataType.INT8 || inputType == DataType.UINT8) {
            "Unsupported model input type: $inputType"
        }
        check(outputType == DataType.FLOAT32 || outputType == DataType.INT8 || outputType == DataType.UINT8) {
            "Unsupported model output type: $outputType"
        }
        inputElements = inputTensor.numElements()
        outputElements = outputTensor.numElements()
        inputScale = inputTensor.quantizationParams().scale
        inputZeroPoint = inputTensor.quantizationParams().zeroPoint
        outputScale = outputTensor.quantizationParams().scale
        outputZeroPoint = outputTensor.quantizationParams().zeroPoint
        if (inputType != DataType.FLOAT32) check(inputScale > 0f) { "Invalid quantized input scale" }
        if (outputType != DataType.FLOAT32) check(outputScale > 0f) { "Invalid quantized output scale" }
        labels = context.assets.open("distress_multilingual_labels.txt").bufferedReader().useLines {
            it.map(String::trim).filter(String::isNotEmpty).toList()
        }
        check(labels.size == outputElements) {
            "Model output has $outputElements values but labels file has ${labels.size} labels"
        }
    }

    override fun infer(pcm16: ShortArray): ModelScores {
        val input = ByteBuffer.allocateDirect(inputElements * bytesPerElement(inputType))
            .order(ByteOrder.nativeOrder())
        val start = max(0, pcm16.size - inputElements)
        val copied = minOf(inputElements, pcm16.size)
        repeat(inputElements) { index ->
            val sourceIndex = index - (inputElements - copied)
            val value = if (sourceIndex >= 0) pcm16[start + sourceIndex] / 32768f else 0f
            writeInput(input, value)
        }
        input.rewind()

        val output = ByteBuffer.allocateDirect(outputElements * bytesPerElement(outputType))
            .order(ByteOrder.nativeOrder())
        interpreter.run(input, output)
        output.rewind()
        val scores = FloatArray(outputElements) { readOutput(output) }

        var distress = 0f
        var keyword = 0f
        val matched = mutableListOf<String>()
        labels.forEachIndexed { index, rawLabel ->
            val label = rawLabel.lowercase(Locale.ROOT)
            val score = scores[index]
            val distressLabel = DISTRESS_TERMS.any(label::contains)
            val keywordLabel = HELP_TERMS.any(label::contains)
            if (distressLabel) distress = max(distress, score)
            if (keywordLabel) keyword = max(keyword, score)
            if (score >= 0.82f && (distressLabel || keywordLabel)) matched += rawLabel
        }
        return ModelScores(
            distressConfidence = distress,
            keywordConfidence = keyword,
            combinedConfidence = max(distress, keyword),
            matchedLabels = matched,
            explicitHelpKeyword = labels.withIndex().any { (index, rawLabel) ->
                isCanonicalHelpLabel(rawLabel) && scores[index] >= RapidEscalationPolicy.RAPID_KEYWORD_THRESHOLD
            },
            strongDistressLabel = labels.withIndex().any { (index, rawLabel) ->
                isCanonicalDistressLabel(rawLabel) && scores[index] >= RapidEscalationPolicy.STRONG_DISTRESS_THRESHOLD
            }
        )
    }

    override fun close() = interpreter.close()

    private fun writeInput(buffer: ByteBuffer, value: Float) {
        when (inputType) {
            DataType.FLOAT32 -> buffer.putFloat(value)
            DataType.INT8 -> buffer.put((value / inputScale + inputZeroPoint).toInt().coerceIn(-128, 127).toByte())
            DataType.UINT8 -> buffer.put((value / inputScale + inputZeroPoint).toInt().coerceIn(0, 255).toByte())
            else -> error("Unsupported input type: $inputType")
        }
    }

    private fun readOutput(buffer: ByteBuffer): Float {
        val value = when (outputType) {
            DataType.FLOAT32 -> buffer.float
            DataType.INT8 -> (buffer.get().toInt() - outputZeroPoint) * outputScale
            DataType.UINT8 -> ((buffer.get().toInt() and 0xFF) - outputZeroPoint) * outputScale
            else -> error("Unsupported output type: $outputType")
        }
        check(value.isFinite() && value in 0f..1f) { "Model output is not a calibrated probability" }
        return value
    }

    private fun bytesPerElement(type: DataType): Int = when (type) {
        DataType.FLOAT32 -> Float.SIZE_BYTES
        DataType.INT8, DataType.UINT8 -> 1
        else -> error("Unsupported tensor type: $type")
    }

    private fun createInterpreter(model: ByteBuffer): Interpreter {
        return try {
            Interpreter(
                model,
                Interpreter.Options().apply {
                    setNumThreads(2)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setUseNNAPI(true)
                }
            )
        } catch (_: Exception) {
            model.rewind()
            Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })
        }
    }

    private fun isCanonicalHelpLabel(rawLabel: String): Boolean =
        HELP_TERMS.contains(rawLabel.trim().lowercase(Locale.ROOT))

    private fun isCanonicalDistressLabel(rawLabel: String): Boolean =
        STRONG_DISTRESS_TERMS.contains(rawLabel.trim().lowercase(Locale.ROOT))

    companion object {
        private val DISTRESS_TERMS = setOf(
            "distress", "scream", "cry", "sob", "hyperventilation", "struggle", "panic",
            "screaming_vocal_friction", "hyperventilation_gasp", "physical_struggle_thud",
            "physical_restraint_groan", "suffocation_stridor"
        )
        private val STRONG_DISTRESS_TERMS = setOf(
            "distress", "scream", "cry", "sob", "hyperventilation", "struggle", "panic",
            "screaming_vocal_friction", "hyperventilation_gasp", "physical_struggle_thud",
            "physical_restraint_groan", "suffocation_stridor"
        )
        private val HELP_TERMS = setOf(
            "help", "help me", "madad", "मदद", "bachao", "bachaao", "बचाओ",
            "sahayata", "सहायता", "amader bachao", "banchao", "udavi", "sahaaya"
        )
    }
}

/**
 * Debounces model output. Inference is deliberately serialized and called only
 * after Tier 1 has remained anomalous for a complete evaluation interval.
 */
class DistressDetectionPipeline(
    private val model: DistressModel,
    private val confidenceThreshold: Float = 0.82f,
    private val requiredWindows: Int = 2,
    private val evaluationWindowMs: Long = 1_500L,
    private val scoreAdjuster: (ModelScores, TierOneFeatures?) -> ModelScores = { scores, _ -> scores }
) : Closeable {
    private var lastEvaluationAt = 0L
    private var consecutiveQualifying = 0
    private var validated = false

    @Synchronized
    fun evaluate(
        pcm16: ShortArray,
        tierOneAnomalous: Boolean,
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): PipelineResult {
        return evaluateInternal(pcm16, tierOneAnomalous, nowElapsedMs, fastTrack = false, tierOne = null)
    }

    @Synchronized
    fun evaluate(
        pcm16: ShortArray,
        tierOne: TierOneFeatures,
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): PipelineResult {
        return evaluateInternal(pcm16, tierOne.candidate, nowElapsedMs, tierOne.fastTrack, tierOne.reasons, tierOne)
    }

    @Synchronized
    fun evaluateRapid(
        pcm16: ShortArray,
        tierOne: TierOneFeatures,
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): PipelineResult {
        if (!tierOne.candidate) return PipelineResult(null, 0, false, tierOneReasons = tierOne.reasons)
        return try {
            val rawScores = model.infer(pcm16)
            val adjustedScores = scoreAdjuster(rawScores, tierOne)
            val decision = RapidEscalationPolicy.decide(tierOne, rawScores, adjustedScores)
            PipelineResult(
                scores = adjustedScores,
                consecutiveQualifyingWindows = consecutiveQualifying,
                validated = decision.shouldPreAlert,
                tierOneReasons = tierOne.reasons,
                fastTrack = tierOne.fastTrack,
                rawScores = rawScores,
                rapidReason = decision.reason
            )
        } catch (error: Exception) {
            PipelineResult(
                scores = null,
                consecutiveQualifyingWindows = consecutiveQualifying,
                validated = false,
                modelError = error.message ?: error.javaClass.simpleName,
                tierOneReasons = tierOne.reasons,
                fastTrack = tierOne.fastTrack
            )
        }
    }

    private fun evaluateInternal(
        pcm16: ShortArray,
        tierOneAnomalous: Boolean,
        nowElapsedMs: Long,
        fastTrack: Boolean,
        reasons: Set<TierOneReason> = emptySet(),
        tierOne: TierOneFeatures? = null
    ): PipelineResult {
        if (!tierOneAnomalous) {
            consecutiveQualifying = 0
            validated = false
            return PipelineResult(null, 0, false, tierOneReasons = reasons, fastTrack = fastTrack)
        }
        val cadenceMs = if (fastTrack) evaluationWindowMs / 2L else evaluationWindowMs
        if (nowElapsedMs - lastEvaluationAt < cadenceMs) {
            return PipelineResult(null, consecutiveQualifying, validated, tierOneReasons = reasons, fastTrack = fastTrack)
        }
        lastEvaluationAt = nowElapsedMs

        return try {
            val rawScores = model.infer(pcm16)
            val scores = scoreAdjuster(rawScores, tierOne)
            if (scores.combinedConfidence >= confidenceThreshold) {
                consecutiveQualifying++
            } else {
                consecutiveQualifying = 0
            }
            validated = consecutiveQualifying >= requiredWindows
            PipelineResult(
                scores = scores,
                consecutiveQualifyingWindows = consecutiveQualifying,
                validated = validated,
                tierOneReasons = reasons,
                fastTrack = fastTrack,
                rawScores = rawScores
            )
        } catch (error: Exception) {
            consecutiveQualifying = 0
            validated = false
            PipelineResult(
                null,
                0,
                false,
                error.message ?: error.javaClass.simpleName,
                reasons,
                fastTrack,
                rawScores = null
            )
        }
    }

    @Synchronized
    fun reset() {
        lastEvaluationAt = 0L
        consecutiveQualifying = 0
        validated = false
    }

    override fun close() = model.close()
}
