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
    }

    fun enrich(features: TierOneFeatures): TierOneFeatures {
        val motionScore = (0.65f * accelerationSpike + 0.35f * gyroscopeSpike).coerceIn(0f, 1f)
        val boost = if (TierOneReason.IMPACT_TRANSIENT in features.reasons) motionScore else 0f
        return features.copy(motionShockScore = motionScore, sensorFusionBoost = boost)
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
            }
            Sensor.TYPE_GYROSCOPE -> {
                val magnitude = magnitude(event.values)
                val spike = ((magnitude - 1.5f) / 6f).coerceIn(0f, 1f)
                gyroscopeSpike = maxOf(spike, gyroscopeSpike * 0.92f)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun magnitude(values: FloatArray): Float =
        sqrt(values.fold(0f) { sum, value -> sum + value * value })
}
