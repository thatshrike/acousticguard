package com.acousticguard

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.sqrt

/**
 * A small, encrypted, cross-process owner voice profile.
 *
 * Only normalized embeddings and bounded scalar statistics are persisted. Raw
 * PCM is never accepted by this class. Android Keystore protects the profile at
 * rest; if the keystore or payload is unavailable, callers fail closed.
 */
class EncryptedOwnerVoiceProfileStore(context: Context) : OwnerVoiceProfileStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
    )
    private val samples = ArrayList<FloatArray>(MAX_SAMPLES)
    private var dimension: Int? = null
    private var accumulatedStatistics = OwnerVoiceStatistics()

    init {
        load()
    }

    @Synchronized
    override fun addEnrollmentSample(
        embedding: FloatArray,
        statistics: OwnerVoiceStatistics
    ): Boolean {
        if (samples.size >= MAX_SAMPLES) return false
        val normalized = normalize(embedding) ?: return false
        if (dimension != null && dimension != normalized.size) return false
        val previousStatistics = accumulatedStatistics
        dimension = normalized.size
        samples += normalized
        accumulatedStatistics = averageStatistics(accumulatedStatistics, statistics, samples.size)
        if (persist()) return true
        samples.removeAt(samples.lastIndex)
        dimension = samples.firstOrNull()?.size
        accumulatedStatistics = previousStatistics
        return false
    }

    @Synchronized
    override fun clear() {
        samples.clear()
        dimension = null
        accumulatedStatistics = OwnerVoiceStatistics()
        preferences.edit().remove(KEY_PROFILE).commit()
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
        val normalized = normalize(embedding)
        if (normalized == null || samples.isEmpty() || normalized.size != dimension) {
            return OwnerVoiceMatch(samples.isNotEmpty(), 0f, false, SIMILARITY_THRESHOLD)
        }
        val similarity = (samples.sumOf { cosine(it, normalized).toDouble() } / samples.size)
            .toFloat()
            .coerceIn(0f, 1f)
        return OwnerVoiceMatch(
            enrolled = true,
            similarity = similarity,
            matched = similarity >= SIMILARITY_THRESHOLD,
            threshold = SIMILARITY_THRESHOLD
        )
    }

    private fun persist(): Boolean {
        return try {
            val encrypted = encrypt(serialize())
            preferences.edit()
                .putString(KEY_PROFILE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    private fun load() {
        val encoded = preferences.getString(KEY_PROFILE, null) ?: return
        try {
            deserialize(decrypt(Base64.decode(encoded, Base64.NO_WRAP)))
        } catch (_: Exception) {
            clear()
        }
    }

    private fun serialize(): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(FORMAT_VERSION)
            data.writeInt(samples.size)
            samples.forEach { sample ->
                data.writeInt(sample.size)
                sample.forEach(data::writeFloat)
            }
            data.writeFloat(accumulatedStatistics.meanRms)
            data.writeFloat(accumulatedStatistics.meanZeroCrossingRate)
            data.writeFloat(accumulatedStatistics.meanHighFrequencyRatio)
        }
        return output.toByteArray()
    }

    private fun deserialize(bytes: ByteArray) {
        val loaded = ArrayList<FloatArray>(MAX_SAMPLES)
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            check(data.readInt() == FORMAT_VERSION)
            val count = data.readInt()
            check(count in 0..MAX_SAMPLES)
            repeat(count) {
                val size = data.readInt()
                check(size in 1..MAX_EMBEDDING_DIMENSION)
                val sample = FloatArray(size) { data.readFloat() }
                loaded += normalize(sample) ?: error("Invalid stored owner profile")
            }
            val stats = OwnerVoiceStatistics(
                meanRms = data.readFloat(),
                meanZeroCrossingRate = data.readFloat(),
                meanHighFrequencyRatio = data.readFloat()
            )
            check(loaded.map { it.size }.distinct().size <= 1)
            samples.clear()
            samples += loaded
            dimension = loaded.firstOrNull()?.size
            accumulatedStatistics = stats
        }
    }

    private fun normalize(embedding: FloatArray): FloatArray? {
        if (embedding.isEmpty() || embedding.size > MAX_EMBEDDING_DIMENSION) return null
        var normSquared = 0.0
        embedding.forEach { value ->
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

    private fun cosine(left: FloatArray, right: FloatArray): Float {
        if (left.size != right.size) return 0f
        var dot = 0f
        left.indices.forEach { index -> dot += left[index] * right[index] }
        return dot.coerceIn(-1f, 1f)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(ciphertext: ByteArray): ByteArray {
        check(ciphertext.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(TAG_BITS, ciphertext.copyOf(IV_BYTES))
        )
        return cipher.doFinal(ciphertext.copyOfRange(IV_BYTES, ciphertext.size))
    }

    companion object {
        private const val PREFERENCES = "acousticguard_owner_voice"
        private const val KEY_PROFILE = "encrypted_profile"
        private const val KEY_ALIAS = "acousticguard_owner_voice_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION = 1
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val MAX_SAMPLES = 8
        private const val MAX_EMBEDDING_DIMENSION = 256
        private const val SIMILARITY_THRESHOLD = 0.82f
    }
}
