package com.acousticguard

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

class FalsePositiveLogger(context: Context) {
    private val prefs = context.getSharedPreferences("acousticguard_events", Context.MODE_PRIVATE)

    fun recordFalsePositive(labels: List<String>) {
        append("false_positive", labels)
    }

    fun recordDispatchScheduled(eventId: String, labels: List<String>) {
        append("dispatch_scheduled:$eventId", labels)
    }

    private fun append(type: String, labels: List<String>) {
        val old = runCatching { JSONArray(prefs.getString(KEY_EVENTS, "[]")) }.getOrDefault(JSONArray())
        old.put(
            JSONObject()
                .put("type", type)
                .put("elapsed_ms", SystemClock.elapsedRealtime())
                .put("labels", JSONArray(labels))
        )
        // Keep the local audit bounded. It contains no PCM audio and no message content.
        while (old.length() > 100) old.remove(0)
        prefs.edit().putString(KEY_EVENTS, old.toString()).apply()
    }

    companion object {
        private const val KEY_EVENTS = "event_log"
    }
}
