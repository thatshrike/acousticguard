package com.acousticguard

import android.content.Context
import android.os.Build
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
    val keyVersion: Int,
    val counter: Long,
    val timestampEpochMs: Long,
    val locationCapturedAtEpochMs: Long,
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
        signatureFlags: Int,
        locationCapturedAtEpochMs: Long? = null
    ): ByteArray {
        val keyVersion = currentKeyVersion()
        val counter = nextCounter()
        val plain = ByteBuffer.allocate(PLAINTEXT_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
            put(VERSION)
            put(keyVersion.toByte())
            putLong(counter)
            putLong(eventId.mostSignificantBits)
            putLong(eventId.leastSignificantBits)
            putLong(timestampEpochMs)
            putLong(locationCapturedAtEpochMs ?: 0L)
            putFloat(latitude ?: Float.NaN)
            putFloat(longitude ?: Float.NaN)
            putShort(signatureFlags.toShort())
        }.array()
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(authenticatedData(keyVersion))
        }
        val encrypted = cipher.doFinal(plain)
        val envelope = ByteBuffer.allocate(2 + NONCE_BYTES + encrypted.size)
            .put(VERSION)
            .put(keyVersion.toByte())
            .put(nonce)
            .put(encrypted)
            .array()
        check(envelope.size <= MAX_ENVELOPE_BYTES) { "SOS envelope exceeds BLE transport budget" }
        return envelope
    }

    fun decrypt(envelope: ByteArray): DecodedSosPayload? {
        if (envelope.size < 2 + NONCE_BYTES + TAG_BYTES || envelope[0] != VERSION) return null
        return runCatching {
            val keyVersion = envelope[1].toInt() and 0xFF
            if (keyVersion == 0) return null
            val nonce = envelope.copyOfRange(2, 2 + NONCE_BYTES)
            val ciphertext = envelope.copyOfRange(2 + NONCE_BYTES, envelope.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(keyVersion), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(authenticatedData(keyVersion))
            }
            val plain = cipher.doFinal(ciphertext)
            if (plain.size != PLAINTEXT_BYTES || plain[0] != VERSION || (plain[1].toInt() and 0xFF) != keyVersion) return null
            val decoded = ByteBuffer.wrap(plain).order(ByteOrder.BIG_ENDIAN).apply { position(1) }
            decoded.get()
            val counter = decoded.long
            val eventId = UUID(decoded.long, decoded.long)
            val replayKey = "$keyVersion:$counter:$eventId"
            val acceptedEvents = preferences.getStringSet(KEY_ACCEPTED_EVENTS, emptySet()).orEmpty().toMutableSet()
            if (!acceptedEvents.add(replayKey)) return null
            val timestamp = decoded.long
            val locationCapturedAt = decoded.long
            val latitude = decoded.float
            val longitude = decoded.float
            val flags = decoded.short.toInt() and 0xFFFF
            while (acceptedEvents.size > MAX_ACCEPTED_EVENTS) acceptedEvents.remove(acceptedEvents.first())
            preferences.edit().putStringSet(KEY_ACCEPTED_EVENTS, acceptedEvents).apply()
            DecodedSosPayload(eventId, keyVersion, counter, timestamp, locationCapturedAt, latitude, longitude, flags)
        }.getOrNull()
    }

    fun rotateKey(): Int {
        val next = (currentKeyVersion() + 1).coerceIn(1, MAX_KEY_VERSION)
        preferences.edit().putInt(KEY_KEY_VERSION, next).commit()
        return next
    }

    fun currentKeyVersion(): Int = preferences.getInt(KEY_KEY_VERSION, 1).coerceIn(1, MAX_KEY_VERSION)

    private fun key(version: Int = currentKeyVersion()): SecretKey {
        require(version in 1..MAX_KEY_VERSION)
        val alias = "$keyAlias-$version"
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return generateKey(alias, strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    }

    private fun generateKey(alias: String, strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val base = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        return try {
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                base.setIsStrongBoxBacked(true)
            }
            generator.init(base.build())
            generator.generateKey()
        } catch (_: Exception) {
            // StrongBox is optional hardware. Fall back to a hardware-backed or
            // software Keystore implementation when the device lacks it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) base.setIsStrongBoxBacked(false)
            generator.init(base.build())
            generator.generateKey()
        }
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
        private const val KEY_KEY_VERSION = "key_version"
        private const val KEY_ACCEPTED_EVENTS = "accepted_events"
        private const val MAX_ACCEPTED_EVENTS = 256
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
        private const val MAX_ENVELOPE_BYTES = 128
        private const val MAX_KEY_VERSION = 255
        private const val PLAINTEXT_BYTES = 1 + 1 + 8 + 16 + 8 + 8 + 4 + 4 + 2
        private val VERSION = 1.toByte()
        private val AUTHENTICATED_DATA = "AcousticGuard-SOS-v1".toByteArray(Charsets.US_ASCII)
    }

    private fun authenticatedData(keyVersion: Int): ByteArray =
        AUTHENTICATED_DATA + byteArrayOf(keyVersion.toByte())
}
