package com.acousticguard

import android.content.Context
import kotlin.math.min

/** Stores only calibration statistics; raw audio is never persisted. */
class AdaptiveThresholdStore(context: Context) {
    private val prefs = context.getSharedPreferences("acousticguard_calibration", Context.MODE_PRIVATE)

    fun rmsMultiplier(): Float = prefs.getFloat(KEY_MULTIPLIER, 2.4f)

    fun recordFalsePositive() {
        val next = min(4.0f, rmsMultiplier() + 0.15f)
        prefs.edit().putFloat(KEY_MULTIPLIER, next).apply()
    }

    fun recordConfirmedEvent() {
        val next = (rmsMultiplier() - 0.02f).coerceAtLeast(2.0f)
        prefs.edit().putFloat(KEY_MULTIPLIER, next).apply()
    }

    companion object {
        private const val KEY_MULTIPLIER = "rms_multiplier"
    }
}
