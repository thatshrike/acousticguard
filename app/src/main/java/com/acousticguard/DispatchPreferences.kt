package com.acousticguard

import android.content.Context

class DispatchPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("acousticguard_dispatch", Context.MODE_PRIVATE)

    var phoneNumber: String
        get() = prefs.getString(KEY_PHONE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PHONE, value.trim()).apply()

    var httpsEndpoint: String
        get() = prefs.getString(KEY_ENDPOINT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ENDPOINT, value.trim()).apply()

    var quickPin: String
        get() = prefs.getString(KEY_PIN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PIN, value.filter(Char::isDigit)).apply()

    companion object {
        private const val KEY_PHONE = "phone_number"
        private const val KEY_ENDPOINT = "https_endpoint"
        private const val KEY_PIN = "quick_pin"
    }
}
