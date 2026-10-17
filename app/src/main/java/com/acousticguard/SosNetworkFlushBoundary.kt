package com.acousticguard

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Android HTTPS adapter for the transport-neutral encrypted SOS outbox. */
class SosNetworkFlushBoundary(context: Context) {
    private val appContext = context.applicationContext
    private val queue = StoreAndForwardQueue(SharedPreferencesStoreAndForwardStorage(appContext))

    fun enqueueEncryptedPayload(
        eventId: String,
        encryptedPayload: ByteArray,
        expiresAtEpochMs: Long = safeAdd(
            System.currentTimeMillis(),
            StoreAndForwardQueue.DEFAULT_EVENT_TTL_MS
        )
    ): EnqueueResult = queue.enqueue(eventId, encryptedPayload, expiresAtEpochMs)

    /**
     * Called by the current SOS receiver for an immediate bounded attempt and
     * by a future connectivity-aware worker/service when the network returns.
     */
    fun onConnectivityAvailable(
        maxEvents: Int = StoreAndForwardQueue.DEFAULT_MAX_ENTRIES,
        now: Long = System.currentTimeMillis()
    ): QueueFlushResult {
        require(maxEvents > 0) { "maxEvents must be positive" }
        val endpoint = DispatchPreferences(appContext).httpsEndpoint
        if (!endpoint.startsWith("https://", ignoreCase = true)) {
            return QueueFlushResult(
                attempted = 0,
                delivered = 0,
                deferred = 0,
                expired = queue.purgeExpired(now)
            )
        }
        return SosQueueFlushBoundary(
            queue = queue,
            deliver = { event -> postHttps(endpoint, event) },
            maxEventsPerFlush = maxEvents
        ).onConnectivityAvailable(now)
    }

    private fun postHttps(endpoint: String, event: QueuedSosEvent): Boolean {
        val payload = JSONObject()
            .put("protocol_version", 1)
            .put("event_id", event.eventId)
            .put(
                "encrypted_payload_b64",
                Base64.encodeToString(event.encryptedPayload, Base64.NO_WRAP)
            )
            .toString()
        val connection = runCatching {
            (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2_500
                readTimeout = 5_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }.getOrElse {
            Log.e(TAG, "Could not open HTTPS SOS endpoint", it)
            return false
        }
        return try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            connection.responseCode in 200..299
        } catch (error: Exception) {
            Log.e(TAG, "HTTPS queued SOS delivery failed", error)
            false
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "AcousticGuardDispatch"

        private fun safeAdd(value: Long, increment: Long): Long =
            if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
    }
}
