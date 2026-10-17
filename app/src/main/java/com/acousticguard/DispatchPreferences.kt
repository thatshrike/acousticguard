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

    var quietAlertMode: Boolean
        get() = prefs.getBoolean(KEY_QUIET_ALERT, false)
        set(value) = prefs.edit().putBoolean(KEY_QUIET_ALERT, value).apply()

    var communityMeshEnabled: Boolean
        get() = prefs.getBoolean(KEY_MESH, false)
        set(value) = prefs.edit().putBoolean(KEY_MESH, value).apply()

    companion object {
        private const val KEY_PHONE = "phone_number"
        private const val KEY_ENDPOINT = "https_endpoint"
        private const val KEY_PIN = "quick_pin"
        private const val KEY_QUIET_ALERT = "quiet_alert_mode"
        private const val KEY_MESH = "community_mesh_enabled"
    }
}
