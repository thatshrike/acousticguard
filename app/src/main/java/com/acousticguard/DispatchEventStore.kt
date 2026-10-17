package com.acousticguard

import android.content.Context

/** Small idempotency journal for exact-alarm events; it stores metadata, never PCM. */
class DispatchEventStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun markScheduled(eventId: String): Boolean {
        val current = prefs.getString(stateKey(eventId), null)
        if (current == STATE_CANCELED || current == STATE_DISPATCHING || current == STATE_ATTEMPTED) return false
        prefs.edit().putString(stateKey(eventId), STATE_SCHEDULED).apply()
        return true
    }

    @Synchronized
    fun claimForDispatch(eventId: String): Boolean {
        if (prefs.getString(stateKey(eventId), null) != STATE_SCHEDULED) return false
        prefs.edit().putString(stateKey(eventId), STATE_DISPATCHING).apply()
        return true
    }

    fun markAttempted(eventId: String) {
        prefs.edit().putString(stateKey(eventId), STATE_ATTEMPTED).apply()
    }

    fun markCanceled(eventId: String) {
        prefs.edit().putString(stateKey(eventId), STATE_CANCELED).apply()
    }

    fun markFailed(eventId: String) {
        prefs.edit().putString(stateKey(eventId), STATE_FAILED).apply()
    }

    private fun stateKey(eventId: String) = "state:$eventId"

    companion object {
        private const val PREFERENCES = "acousticguard_dispatch_events"
        private const val STATE_SCHEDULED = "scheduled"
        private const val STATE_DISPATCHING = "dispatching"
        private const val STATE_ATTEMPTED = "attempted"
        private const val STATE_CANCELED = "canceled"
        private const val STATE_FAILED = "failed"
    }
}
