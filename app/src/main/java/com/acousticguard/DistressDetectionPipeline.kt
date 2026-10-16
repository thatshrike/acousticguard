package com.acousticguard

import android.content.Context
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
    val matchedLabels: List<String>
)

data class PipelineResult(
    val scores: ModelScores?,
    val consecutiveQualifyingWindows: Int,
    val validated: Boolean,
    val modelError: String? = null
)

interface DistressModel : Closeable {
    fun infer(pcm16: ShortArray): ModelScores
}

/**
 * TFLite adapter for the checked-in model contract:
 * - input tensor: FLOAT32 mono PCM normalized to [-1, 1]
 * - output tensor 0: FLOAT32 scores, shape [1, label_count] or [label_count]
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

    init {
        val model = context.assets.open("distress_multilingual.tflite").use { stream ->
            val bytes = stream.readBytes()
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
        }
        interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })
        check(interpreter.getInputTensor(0).dataType() == DataType.FLOAT32) {
            "AcousticGuard model input must be FLOAT32"
        }
        check(interpreter.getOutputTensor(0).dataType() == DataType.FLOAT32) {
            "AcousticGuard model output must be FLOAT32"
        }
        inputElements = interpreter.getInputTensor(0).numElements()
        outputElements = interpreter.getOutputTensor(0).numElements()
        labels = context.assets.open("distress_multilingual_labels.txt").bufferedReader().useLines {
            it.map(String::trim).filter(String::isNotEmpty).toList()
        }
        check(labels.size == outputElements) {
            "Model output has $outputElements values but labels file has ${labels.size} labels"
        }
    }

    override fun infer(pcm16: ShortArray): ModelScores {
        val input = ByteBuffer.allocateDirect(inputElements * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        val normalized = FloatArray(inputElements)
        val start = max(0, pcm16.size - inputElements)
        val copied = minOf(inputElements, pcm16.size)
        for (i in 0 until copied) normalized[i] = pcm16[start + i] / 32768f
        normalized.forEach(input::putFloat)
        input.rewind()

        val output = ByteBuffer.allocateDirect(outputElements * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        interpreter.run(input, output)
        output.rewind()
        val scores = FloatArray(outputElements) { output.float.coerceIn(0f, 1f) }

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
            matchedLabels = matched
        )
    }

    override fun close() = interpreter.close()

    companion object {
        private val DISTRESS_TERMS = setOf(
            "distress", "scream", "cry", "sob", "hyperventilation", "struggle", "panic"
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
    private val evaluationWindowMs: Long = 1_500L
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
        if (!tierOneAnomalous) {
            consecutiveQualifying = 0
            validated = false
            return PipelineResult(null, 0, false)
        }
        if (nowElapsedMs - lastEvaluationAt < evaluationWindowMs) {
            return PipelineResult(null, consecutiveQualifying, validated)
        }
        lastEvaluationAt = nowElapsedMs

        return try {
            val scores = model.infer(pcm16)
            if (scores.combinedConfidence >= confidenceThreshold) {
                consecutiveQualifying++
            } else {
                consecutiveQualifying = 0
            }
            validated = consecutiveQualifying >= requiredWindows
            PipelineResult(scores, consecutiveQualifying, validated)
        } catch (error: Exception) {
            consecutiveQualifying = 0
            validated = false
            PipelineResult(null, 0, false, error.message ?: error.javaClass.simpleName)
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
