package com.acousticguard

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Optional voice-embedding contract. Implementations return null when an
 * embedding cannot be produced; callers must then fail closed and avoid owner
 * voice suppression.
 */
interface VoiceEmbeddingModel : Closeable {
    fun embed(pcm16: ShortArray, sampleRate: Int = 16_000): FloatArray?
}

/**
 * TFLite adapter for an optional, application-supplied embedding asset.
 *
 * No model binary is bundled here. The asset must expose one mono PCM input
 * tensor and an embedding output tensor with FLOAT32, INT8, or UINT8 values.
 * Missing assets, unsupported tensors, malformed output, and inference errors
 * all produce a closed/fail-closed adapter rather than an owner match.
 */
class TfliteVoiceEmbeddingModel(
    context: Context,
    private val assetName: String = DEFAULT_ASSET_NAME
) : VoiceEmbeddingModel {
    private val interpreter: Interpreter?
    private val inputType: DataType?
    private val outputType: DataType?
    private val inputElements: Int
    private val outputElements: Int
    private val inputScale: Float
    private val inputZeroPoint: Int
    private val outputScale: Float
    private val outputZeroPoint: Int
    @Volatile private var closed = false

    /** False means this adapter is unavailable and must not suppress distress. */
    val isReady: Boolean
        get() = interpreter != null && !closed

    init {
        var localInterpreter: Interpreter? = null
        var localInputType: DataType? = null
        var localOutputType: DataType? = null
        var localInputElements = 0
        var localOutputElements = 0
        var localInputScale = 0f
        var localInputZeroPoint = 0
        var localOutputScale = 0f
        var localOutputZeroPoint = 0
        try {
            val model = context.applicationContext.assets.open(assetName).use { stream ->
                val bytes = stream.readBytes()
                ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                    put(bytes)
                    rewind()
                }
            }
            localInterpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })
            val inputTensor = localInterpreter.getInputTensor(0)
            val outputTensor = localInterpreter.getOutputTensor(0)
            localInputType = inputTensor.dataType()
            localOutputType = outputTensor.dataType()
            check(localInputType == DataType.FLOAT32 || localInputType == DataType.INT8 || localInputType == DataType.UINT8) {
                "Unsupported voice embedding input type: $localInputType"
            }
            check(localOutputType == DataType.FLOAT32 || localOutputType == DataType.INT8 || localOutputType == DataType.UINT8) {
                "Unsupported voice embedding output type: $localOutputType"
            }
            localInputElements = inputTensor.numElements()
            localOutputElements = outputTensor.numElements()
            check(localInputElements > 0 && localOutputElements > 0) { "Embedding tensors must be non-empty" }
            check(localInputElements <= MAX_INPUT_ELEMENTS) { "Voice input tensor is too large" }
            check(localOutputElements <= MAX_OUTPUT_ELEMENTS) { "Voice embedding tensor is too large" }
            localInputScale = inputTensor.quantizationParams().scale
            localInputZeroPoint = inputTensor.quantizationParams().zeroPoint
            localOutputScale = outputTensor.quantizationParams().scale
            localOutputZeroPoint = outputTensor.quantizationParams().zeroPoint
            if (localInputType != DataType.FLOAT32) check(localInputScale > 0f) { "Invalid voice input scale" }
            if (localOutputType != DataType.FLOAT32) check(localOutputScale > 0f) { "Invalid voice output scale" }
        } catch (_: Exception) {
            localInterpreter?.close()
            localInterpreter = null
            localInputType = null
            localOutputType = null
            localInputElements = 0
            localOutputElements = 0
        }
        interpreter = localInterpreter
        inputType = localInputType
        outputType = localOutputType
        inputElements = localInputElements
        outputElements = localOutputElements
        inputScale = localInputScale
        inputZeroPoint = localInputZeroPoint
        outputScale = localOutputScale
        outputZeroPoint = localOutputZeroPoint
    }

    @Synchronized
    override fun embed(pcm16: ShortArray, sampleRate: Int): FloatArray? {
        if (!isReady) return null
        val activeInterpreter = interpreter ?: return null
        val activeInputType = inputType ?: return null
        val activeOutputType = outputType ?: return null
        if (sampleRate <= 0 || pcm16.isEmpty() || inputElements <= 0 || outputElements <= 0) return null
        return try {
            val input = ByteBuffer.allocateDirect(inputElements * bytesPerElement(activeInputType))
                .order(ByteOrder.nativeOrder())
            val copied = minOf(inputElements, pcm16.size)
            val start = pcm16.size - copied
            repeat(inputElements) { index ->
                val sourceIndex = index - (inputElements - copied)
                val value = if (sourceIndex >= 0) pcm16[start + sourceIndex] / 32768f else 0f
                writeInput(input, value, activeInputType)
            }
            input.rewind()
            val output = ByteBuffer.allocateDirect(outputElements * bytesPerElement(activeOutputType))
                .order(ByteOrder.nativeOrder())
            activeInterpreter.run(input, output)
            output.rewind()
            FloatArray(outputElements) { readOutput(output, activeOutputType) }.also { embedding ->
                if (embedding.any { !it.isFinite() }) return null
            }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        interpreter?.close()
    }

    private fun writeInput(buffer: ByteBuffer, value: Float, type: DataType) {
        when (type) {
            DataType.FLOAT32 -> buffer.putFloat(value)
            DataType.INT8 -> buffer.put((value / inputScale + inputZeroPoint).toInt().coerceIn(-128, 127).toByte())
            DataType.UINT8 -> buffer.put((value / inputScale + inputZeroPoint).toInt().coerceIn(0, 255).toByte())
            else -> error("Unsupported voice input type: $type")
        }
    }

    private fun readOutput(buffer: ByteBuffer, type: DataType): Float = when (type) {
        DataType.FLOAT32 -> buffer.float
        DataType.INT8 -> (buffer.get().toInt() - outputZeroPoint) * outputScale
        DataType.UINT8 -> ((buffer.get().toInt() and 0xFF) - outputZeroPoint) * outputScale
        else -> error("Unsupported voice output type: $type")
    }

    private fun bytesPerElement(type: DataType): Int = when (type) {
        DataType.FLOAT32 -> Float.SIZE_BYTES
        DataType.INT8, DataType.UINT8 -> 1
        else -> error("Unsupported voice tensor type: $type")
    }

    companion object {
        const val DEFAULT_ASSET_NAME = "owner_voice_embedding.tflite"
        private const val MAX_INPUT_ELEMENTS = 1_000_000
        private const val MAX_OUTPUT_ELEMENTS = 4_096
    }
}
