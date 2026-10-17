package com.acousticguard

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
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
                    context.startService(serviceIntent)
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
        val location = LocationBurstManager(context).lastKnown()
        val encryptedPayload = runCatching {
            SosPayloadCrypto(context).encrypt(
                eventUuid,
                System.currentTimeMillis(),
                location?.latitude?.toFloat(),
                location?.longitude?.toFloat(),
                labels.toSignatureFlags(),
                location?.capturedAtEpochMs
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
            val networkBoundary = SosNetworkFlushBoundary(context)
            val enqueueResult = networkBoundary.enqueueEncryptedPayload(
                eventId = eventId,
                encryptedPayload = encryptedPayload,
                expiresAtEpochMs = System.currentTimeMillis() + QUEUED_EVENT_TTL_MS
            )
            when (enqueueResult) {
                EnqueueResult.ENQUEUED, EnqueueResult.DUPLICATE -> Unit
                EnqueueResult.REJECTED_FULL ->
                    Log.e(TAG, "Encrypted SOS queue is full; queued delivery was rejected")
                EnqueueResult.REJECTED_INVALID, EnqueueResult.REJECTED_EXPIRED ->
                    Log.e(TAG, "Encrypted SOS queue rejected event $eventId: $enqueueResult")
            }
            val flush = networkBoundary.onConnectivityAvailable()
            if (flush.deferred > 0) {
                Log.w(TAG, "HTTPS SOS deferred: ${flush.deferred} envelope(s) remain queued")
            }
        }
    }

    companion object {
        private const val TAG = "AcousticGuardDispatch"
        private const val QUEUED_EVENT_TTL_MS = StoreAndForwardQueue.DEFAULT_EVENT_TTL_MS
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
