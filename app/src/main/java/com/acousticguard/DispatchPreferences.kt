package com.acousticguard

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class DispatchPreferences(context: Context) {
    @Suppress("DEPRECATION")
    private val prefs = context.getSharedPreferences(
        "acousticguard_dispatch",
        Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
    )

    init {
        migrateLegacyPinIfNeeded()
    }

    var phoneNumber: String
        get() = prefs.getString(KEY_PHONE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PHONE, value.trim()).apply()

    var httpsEndpoint: String
        get() = prefs.getString(KEY_ENDPOINT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ENDPOINT, value.trim()).apply()

    var quickPin: String
        get() = ""
        set(value) {
            val pin = value.filter(Char::isDigit)
            if (pin.length !in MIN_PIN_LENGTH..MAX_PIN_LENGTH) return
            val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
            prefs.edit()
                .remove(KEY_LEGACY_PIN)
                .putString(KEY_PIN_SALT, encode(salt))
                .putString(KEY_PIN_HASH, encode(hash(pin, salt)))
                .putString(KEY_DURESS_HASH, encode(hash(pin.reversed(), salt)))
                .apply()
        }

    val pinConfigured: Boolean
        get() = prefs.contains(KEY_PIN_HASH) && prefs.contains(KEY_PIN_SALT)

    fun matchesRealPin(candidate: String): Boolean = matches(candidate, KEY_PIN_HASH)

    /** The reverse of the configured real PIN is the default duress PIN. */
    fun matchesDuressPin(candidate: String): Boolean {
        val normalized = candidate.filter(Char::isDigit)
        if (!pinConfigured || normalized.length !in MIN_PIN_LENGTH..MAX_PIN_LENGTH) return false
        return matchesConfiguredDuressPin(normalized)
    }

    fun configureDuressPin(pin: String) {
        val normalized = pin.filter(Char::isDigit)
        if (normalized.length !in MIN_PIN_LENGTH..MAX_PIN_LENGTH || !pinConfigured) return
        val salt = decode(prefs.getString(KEY_PIN_SALT, "").orEmpty()) ?: return
        prefs.edit().putString(KEY_DURESS_HASH, encode(hash(normalized, salt))).apply()
    }

    fun matchesConfiguredDuressPin(candidate: String): Boolean = matches(candidate, KEY_DURESS_HASH)

    var quietAlertMode: Boolean
        get() = prefs.getBoolean(KEY_QUIET_ALERT, false)
        set(value) = prefs.edit().putBoolean(KEY_QUIET_ALERT, value).apply()

    var communityMeshEnabled: Boolean
        get() = prefs.getBoolean(KEY_MESH, false)
        set(value) = prefs.edit().putBoolean(KEY_MESH, value).apply()

    companion object {
        private const val KEY_PHONE = "phone_number"
        private const val KEY_ENDPOINT = "https_endpoint"
        private const val KEY_LEGACY_PIN = "quick_pin"
        private const val KEY_PIN_HASH = "quick_pin_hash"
        private const val KEY_DURESS_HASH = "duress_pin_hash"
        private const val KEY_PIN_SALT = "quick_pin_salt"
        private const val KEY_QUIET_ALERT = "quiet_alert_mode"
        private const val KEY_MESH = "community_mesh_enabled"
        private const val MIN_PIN_LENGTH = 4
        private const val MAX_PIN_LENGTH = 8
        private const val SALT_BYTES = 16
        private const val PBKDF2_ITERATIONS = 120_000

    }

    private fun matches(candidate: String, key: String): Boolean {
        val normalized = candidate.filter(Char::isDigit)
        val hashValue = decode(prefs.getString(key, "").orEmpty()) ?: return false
        val salt = decode(prefs.getString(KEY_PIN_SALT, "").orEmpty()) ?: return false
        return MessageDigest.isEqual(hash(normalized, salt), hashValue)
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray? = runCatching {
        if (value.isBlank()) null else Base64.decode(value, Base64.NO_WRAP)
    }.getOrNull()

    private fun migrateLegacyPinIfNeeded() {
        if (pinConfigured) return
        val legacy = prefs.getString(KEY_LEGACY_PIN, null)?.filter(Char::isDigit).orEmpty()
        if (legacy.length !in MIN_PIN_LENGTH..MAX_PIN_LENGTH) return
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        prefs.edit()
            .remove(KEY_LEGACY_PIN)
            .putString(KEY_PIN_SALT, encode(salt))
            .putString(KEY_PIN_HASH, encode(hash(legacy, salt)))
            .putString(KEY_DURESS_HASH, encode(hash(legacy.reversed(), salt)))
            .apply()
    }
}
