package com.acousticguard

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class MotionFusion(context: Context) : SensorEventListener {
    private val sensorManager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    @Volatile private var accelerationSpike = 0f
    @Volatile private var gyroscopeSpike = 0f
    private var accelerationPeak = 0f
    private var gyroscopePeak = 0f
    private var accelerationAtMs = 0L
    private var gyroscopeAtMs = 0L

    fun start(): Boolean {
        val manager = sensorManager ?: return false
        var registered = false
        accelerometer?.let {
            registered = manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) || registered
        }
        gyroscope?.let {
            registered = manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) || registered
        }
        return registered
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        accelerationSpike = 0f
        gyroscopeSpike = 0f
        synchronized(this) {
            accelerationPeak = 0f
            gyroscopePeak = 0f
            accelerationAtMs = 0L
            gyroscopeAtMs = 0L
        }
    }

    fun enrich(features: TierOneFeatures): TierOneFeatures {
        val now = android.os.SystemClock.elapsedRealtime()
        val evidence = synchronized(this) {
            val value = MotionEvidence(
                accelerationPeak,
                gyroscopePeak,
                accelerationAtMs,
                gyroscopeAtMs
            )
            accelerationPeak = 0f
            gyroscopePeak = 0f
            accelerationAtMs = 0L
            gyroscopeAtMs = 0L
            value
        }
        val motionScore = (0.65f * evidence.accelerationPeak + 0.35f * evidence.gyroscopePeak)
            .coerceIn(0f, 1f)
        val concurrent = evidence.accelerationPeak > 0f && evidence.gyroscopePeak > 0f &&
            kotlin.math.abs(evidence.accelerationAtMs - evidence.gyroscopeAtMs) <= CONCURRENCY_WINDOW_MS &&
            now - maxOf(evidence.accelerationAtMs, evidence.gyroscopeAtMs) <= EVIDENCE_MAX_AGE_MS
        val boost = if (TierOneReason.IMPACT_TRANSIENT in features.reasons) motionScore else 0f
        return features.copy(
            motionShockScore = motionScore,
            sensorFusionBoost = boost,
            accelerationPeak = evidence.accelerationPeak,
            gyroscopePeak = evidence.gyroscopePeak,
            motionConcurrent = concurrent
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val magnitude = magnitude(event.values)
                // Gravity is removed as a scalar approximation; the score decays
                // between callbacks so one movement cannot stay latched forever.
                val spike = ((kotlin.math.abs(magnitude - SensorManager.GRAVITY_EARTH) - 1.5f) / 8f)
                    .coerceIn(0f, 1f)
                accelerationSpike = maxOf(spike, accelerationSpike * 0.92f)
                if (spike > 0f) synchronized(this) {
                    accelerationPeak = maxOf(accelerationPeak, spike)
                    accelerationAtMs = android.os.SystemClock.elapsedRealtime()
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val magnitude = magnitude(event.values)
                val spike = ((magnitude - 1.5f) / 6f).coerceIn(0f, 1f)
                gyroscopeSpike = maxOf(spike, gyroscopeSpike * 0.92f)
                if (spike > 0f) synchronized(this) {
                    gyroscopePeak = maxOf(gyroscopePeak, spike)
                    gyroscopeAtMs = android.os.SystemClock.elapsedRealtime()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun magnitude(values: FloatArray): Float =
        sqrt(values.fold(0f) { sum, value -> sum + value * value })

    private data class MotionEvidence(
        val accelerationPeak: Float,
        val gyroscopePeak: Float,
        val accelerationAtMs: Long,
        val gyroscopeAtMs: Long
    )

    companion object {
        private const val CONCURRENCY_WINDOW_MS = 350L
        private const val EVIDENCE_MAX_AGE_MS = 750L
    }
}
