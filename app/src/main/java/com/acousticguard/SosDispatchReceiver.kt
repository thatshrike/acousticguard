package com.acousticguard

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.SmsManager
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Runs after the grace period's exact alarm. The alarm wakes this receiver during Doze;
 * it does not make network delivery itself guaranteed, so each channel is logged.
 */
class SosDispatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ExactAlarmScheduler.ACTION_DISPATCH) return
        val pending = goAsync()
        val eventId = intent.getStringExtra(ExactAlarmScheduler.EXTRA_EVENT_ID).orEmpty()
        val labels = intent.getStringArrayExtra(ExactAlarmScheduler.EXTRA_LABELS).orEmpty()
        EXECUTOR.execute {
            try {
                EmergencyDispatcher(context.applicationContext).dispatch(eventId, labels.toList())
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val EXECUTOR = Executors.newCachedThreadPool()
    }
}

private class EmergencyDispatcher(private val context: Context) {
    fun dispatch(eventId: String, labels: List<String>) {
        val prefs = DispatchPreferences(context)
        val payload = buildPayload(eventId, labels)
        if (prefs.phoneNumber.isNotEmpty() &&
            context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                SmsManager.getDefault().sendTextMessage(
                    prefs.phoneNumber,
                    null,
                    "AcousticGuard SOS $eventId. Possible distress detected. Check the person now.",
                    null,
                    null
                )
            }.onFailure { Log.e(TAG, "SMS dispatch failed", it) }
        }
        if (prefs.httpsEndpoint.startsWith("https://", ignoreCase = true)) {
            runCatching { postHttps(prefs.httpsEndpoint, payload) }
                .onFailure { Log.e(TAG, "HTTPS dispatch failed", it) }
        }
    }

    private fun buildPayload(eventId: String, labels: List<String>): String {
        val location = lastKnownCoarseLocation()
        return JSONObject()
            .put("event_id", eventId)
            .put("detected_at_epoch_ms", System.currentTimeMillis())
            .put("labels", labels)
            .put("latitude", location?.first)
            .put("longitude", location?.second)
            .toString()
    }

    private fun lastKnownCoarseLocation(): Pair<Double, Double>? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(LocationManager::class.java)
        val candidates = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        return candidates.asSequence().mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.firstOrNull()?.let { it.latitude to it.longitude }
    }

    private fun postHttps(endpoint: String, payload: String) {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        connection.inputStream.close()
        connection.disconnect()
    }

    @Suppress("UNUSED_VARIABLE")
    private fun networkAvailable(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private const val TAG = "AcousticGuardDispatch"
    }
}
