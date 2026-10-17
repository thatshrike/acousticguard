package com.acousticguard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SosSignatureFlags {
    const val SCREAM = 1
    const val HYPERVENTILATION = 1 shl 1
    const val IMPACT = 1 shl 2
    const val RESTRAINT = 1 shl 3
}

data class DecodedSosPayload(
    val eventId: UUID,
    val counter: Long,
    val timestampEpochMs: Long,
    val latitude: Float,
    val longitude: Float,
    val signatureFlags: Int
)

/**
 * Compact AES-GCM envelope for a provisioned receiver domain. The Android
 * Keystore key is device-local by design; it must be enrolled/shared through an
 * authenticated pairing or server provisioning flow before another phone can
 * decrypt a BLE packet. There is intentionally no plaintext fallback.
 */
class SosPayloadCrypto(context: Context, private val keyAlias: String = DEFAULT_KEY_ALIAS) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    fun encrypt(
        eventId: UUID,
        timestampEpochMs: Long,
        latitude: Float?,
        longitude: Float?,
        signatureFlags: Int
    ): ByteArray {
        val counter = nextCounter()
        val plain = ByteBuffer.allocate(PLAINTEXT_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
            put(VERSION)
            putLong(counter)
            putLong(eventId.mostSignificantBits)
            putLong(eventId.leastSignificantBits)
            putLong(timestampEpochMs)
            putFloat(latitude ?: Float.NaN)
            putFloat(longitude ?: Float.NaN)
            putShort(signatureFlags.toShort())
        }.array()
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(AUTHENTICATED_DATA)
        }
        val encrypted = cipher.doFinal(plain)
        val envelope = ByteBuffer.allocate(1 + NONCE_BYTES + encrypted.size)
            .put(VERSION)
            .put(nonce)
            .put(encrypted)
            .array()
        check(envelope.size <= MAX_ENVELOPE_BYTES) { "SOS envelope exceeds BLE transport budget" }
        return envelope
    }

    fun decrypt(envelope: ByteArray): DecodedSosPayload? {
        if (envelope.size < 1 + NONCE_BYTES + TAG_BYTES || envelope[0] != VERSION) return null
        return runCatching {
            val nonce = envelope.copyOfRange(1, 1 + NONCE_BYTES)
            val ciphertext = envelope.copyOfRange(1 + NONCE_BYTES, envelope.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(AUTHENTICATED_DATA)
            }
            val plain = cipher.doFinal(ciphertext)
            if (plain.size != PLAINTEXT_BYTES || plain[0] != VERSION) return null
            val decoded = ByteBuffer.wrap(plain).order(ByteOrder.BIG_ENDIAN).apply { position(1) }
            val counter = decoded.long
            val eventId = UUID(decoded.long, decoded.long)
            val replayKey = "$counter:$eventId"
            val acceptedEvents = preferences.getStringSet(KEY_ACCEPTED_EVENTS, emptySet()).orEmpty().toMutableSet()
            if (!acceptedEvents.add(replayKey)) return null
            val timestamp = decoded.long
            val latitude = decoded.float
            val longitude = decoded.float
            val flags = decoded.short.toInt() and 0xFFFF
            while (acceptedEvents.size > MAX_ACCEPTED_EVENTS) acceptedEvents.remove(acceptedEvents.first())
            preferences.edit().putStringSet(KEY_ACCEPTED_EVENTS, acceptedEvents).apply()
            DecodedSosPayload(eventId, counter, timestamp, latitude, longitude, flags)
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun nextCounter(): Long {
        val next = preferences.getLong(KEY_COUNTER, 0L) + 1L
        preferences.edit().putLong(KEY_COUNTER, next).apply()
        return next
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "acousticguard_sos_domain_v1"
        private const val PREFERENCES = "acousticguard_crypto"
        private const val KEY_COUNTER = "outgoing_counter"
        private const val KEY_ACCEPTED_EVENTS = "accepted_events"
        private const val MAX_ACCEPTED_EVENTS = 256
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
        private const val MAX_ENVELOPE_BYTES = 128
        private const val PLAINTEXT_BYTES = 1 + 8 + 16 + 8 + 4 + 4 + 2
        private val VERSION = 1.toByte()
        private val AUTHENTICATED_DATA = "AcousticGuard-SOS-v1".toByteArray(Charsets.US_ASCII)
    }
}
