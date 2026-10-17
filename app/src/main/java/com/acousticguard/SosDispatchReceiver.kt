package com.acousticguard

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.telephony.SmsManager
import android.util.Log
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Runs after the grace period's exact alarm. The alarm wakes this receiver during Doze;
 * it does not make network delivery itself guaranteed, so each channel is logged.
 */
class SosDispatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ExactAlarmScheduler.ACTION_DISPATCH) return
        val eventId = intent.getStringExtra(ExactAlarmScheduler.EXTRA_EVENT_ID).orEmpty()
        if (!DispatchEventStore(context).claimForDispatch(eventId)) return
        val pending = goAsync()
        val labels = intent.getStringArrayExtra(ExactAlarmScheduler.EXTRA_LABELS).orEmpty()
        EXECUTOR.execute {
        try {
            EmergencyDispatcher(context.applicationContext).dispatch(eventId, labels.toList())
        } finally {
                runCatching {
                    val serviceIntent = Intent(context, AcousticCaptureService::class.java)
                        .setAction(AcousticCaptureService.ACTION_PRE_ALERT_RESOLVED)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }.onFailure { Log.w("AcousticGuardDispatch", "Could not clear alert state", it) }
                DispatchEventStore(context).markAttempted(eventId)
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
        context.getSystemService(android.app.NotificationManager::class.java).cancel(101)
        val prefs = DispatchPreferences(context)
        val eventUuid = runCatching { UUID.fromString(eventId) }.getOrNull() ?: return
        val location = lastKnownCoarseLocation()
        val encryptedPayload = runCatching {
            SosPayloadCrypto(context).encrypt(
                eventUuid,
                System.currentTimeMillis(),
                location?.first?.toFloat(),
                location?.second?.toFloat(),
                labels.toSignatureFlags()
            )
        }.getOrElse {
            Log.e(TAG, "No authenticated SOS payload was produced", it)
            return
        }

        if (prefs.communityMeshEnabled) {
            CommunityMeshBroadcaster.start(context, encryptedPayload)
        }
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
            val payload = JSONObject()
                .put("protocol_version", 1)
                .put("event_id", eventId)
                .put("encrypted_payload_b64", Base64.encodeToString(encryptedPayload, Base64.NO_WRAP))
                .toString()
            runCatching { postHttps(prefs.httpsEndpoint, payload) }
                .onFailure { Log.e(TAG, "HTTPS dispatch failed", it) }
        }
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
            // Keep total receiver work bounded; durable retry belongs in a future
            // server/WorkManager integration, not an unbounded BroadcastReceiver.
            connectTimeout = 2_500
            readTimeout = 5_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) error("HTTPS endpoint returned $responseCode")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "AcousticGuardDispatch"
    }
}

private fun List<String>.toSignatureFlags(): Int {
    val normalized = joinToString(" ").lowercase()
    var flags = 0
    if (normalized.contains("scream") || normalized.contains("friction")) flags = flags or SosSignatureFlags.SCREAM
    if (normalized.contains("hyperventilation") || normalized.contains("gasp")) flags = flags or SosSignatureFlags.HYPERVENTILATION
    if (normalized.contains("impact") || normalized.contains("thud") || normalized.contains("struggle")) flags = flags or SosSignatureFlags.IMPACT
    if (normalized.contains("restraint") || normalized.contains("stridor")) flags = flags or SosSignatureFlags.RESTRAINT
    return flags
}
