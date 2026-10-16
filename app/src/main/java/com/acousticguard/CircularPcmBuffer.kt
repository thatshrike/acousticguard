package com.acousticguard

/**
 * A bounded, in-memory PCM16 ring. It never writes audio to disk and only retains
 * the configured tail of the stream. All methods are synchronized because the
 * AudioRecord reader and inference executor use different threads.
 */
class CircularPcmBuffer(private val capacityBytes: Int) {
    init {
        require(capacityBytes > 0) { "capacityBytes must be positive" }
        require(capacityBytes % 2 == 0) { "PCM16 capacity must be an even number" }
    }

    private val data = ByteArray(capacityBytes)
    private var writeIndex = 0
    private var size = 0

    @Synchronized
    fun writePcm16(samples: ShortArray, offset: Int = 0, length: Int = samples.size) {
        require(offset >= 0 && length >= 0 && offset + length <= samples.size)
        repeat(length) { index ->
            val sample = samples[offset + index].toInt()
            writeByte((sample and 0xFF).toByte())
            writeByte(((sample ushr 8) and 0xFF).toByte())
        }
    }

    @Synchronized
    fun snapshotPcm16(): ShortArray {
        val sampleCount = size / 2
        val result = ShortArray(sampleCount)
        val start = (writeIndex - size + capacityBytes) % capacityBytes
        for (sample in 0 until sampleCount) {
            val byteIndex = (start + sample * 2) % capacityBytes
            val low = data[byteIndex].toInt() and 0xFF
            val high = data[(byteIndex + 1) % capacityBytes].toInt()
            result[sample] = ((high shl 8) or low).toShort()
        }
        return result
    }

    @Synchronized
    fun clear() {
        data.fill(0)
        writeIndex = 0
        size = 0
    }

    @Synchronized
    fun sizeBytes(): Int = size

    private fun writeByte(value: Byte) {
        data[writeIndex] = value
        writeIndex = (writeIndex + 1) % capacityBytes
        if (size < capacityBytes) size++
    }
}
