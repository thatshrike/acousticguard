package com.acousticguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.max

data class CachedLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val capturedAtEpochMs: Long
) {
    fun ageMs(nowEpochMs: Long = System.currentTimeMillis()): Long =
        max(0L, nowEpochMs - capturedAtEpochMs)
}

/**
 * Best-effort high-accuracy location burst using platform LocationManager.
 * Play-services fused location can be supplied behind this same boundary later,
 * but the core app has no mandatory Google Play dependency. The cache contains
 * coordinates and timing only; it never stores audio.
 */
class LocationBurstManager(context: Context) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val fusedClient: FusedLocationProviderClient? =
        runCatching { LocationServices.getFusedLocationProviderClient(appContext) }.getOrNull()
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var fusedActive = false
    private var lastPersistAt = 0L
    private var stopRunnable: Runnable? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            persist(location, force = false)
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { persist(it, force = false) }
        }
    }

    fun startBurst(durationMs: Long = BURST_DURATION_MS): Boolean {
        if (!hasFineLocationPermission()) return false
        val manager = locationManager ?: return false
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
        if (providers.isEmpty() && fusedClient == null) return false
        stopBurst()
        active = true
        providers.asSequence()
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { persist(it, force = true) }
        val platformRequested = providers.any { provider ->
            runCatching {
                manager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
                true
            }.getOrDefault(false)
        }
        val fusedRequested = runCatching {
            if (fusedClient == null) false
            else {
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                    .setMinUpdateIntervalMillis(500L)
                    .setMaxUpdateDelayMillis(2_000L)
                    .build()
                fusedClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
                fusedActive = true
                true
            }
        }.getOrDefault(false)
        val scheduledStop = Runnable { stopBurst() }
        stopRunnable = scheduledStop
        handler.postDelayed(scheduledStop, durationMs.coerceIn(5_000L, 60_000L))
        if (!platformRequested && !fusedRequested) stopBurst()
        return platformRequested || fusedRequested
    }

    fun stopBurst() {
        stopRunnable?.let(handler::removeCallbacks)
        stopRunnable = null
        if (!active) return
        runCatching { locationManager?.removeUpdates(listener) }
        if (fusedActive) runCatching { fusedClient?.removeLocationUpdates(fusedCallback) }
        fusedActive = false
        active = false
    }

    fun lastKnown(): CachedLocation? {
        val lat = preferences.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val lon = preferences.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        val accuracy = preferences.getFloat(KEY_ACCURACY, Float.NaN)
        val capturedAt = preferences.getLong(KEY_CAPTURED_AT, 0L)
        if (capturedAt <= 0L || !lat.isFinite() || !lon.isFinite()) return null
        return CachedLocation(lat, lon, accuracy, capturedAt)
    }

    fun clear() {
        preferences.edit().remove(KEY_LATITUDE).remove(KEY_LONGITUDE)
            .remove(KEY_ACCURACY).remove(KEY_CAPTURED_AT).apply()
    }

    private fun persist(location: Location, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAt < CACHE_INTERVAL_MS) return
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) return
        lastPersistAt = now
        preferences.edit()
            .putString(KEY_LATITUDE, location.latitude.toString())
            .putString(KEY_LONGITUDE, location.longitude.toString())
            .putFloat(KEY_ACCURACY, location.accuracy)
            .putLong(KEY_CAPTURED_AT, now)
            .apply()
    }

    private fun hasFineLocationPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val PREFERENCES = "acousticguard_location_cache"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_ACCURACY = "accuracy_meters"
        private const val KEY_CAPTURED_AT = "captured_at_epoch_ms"
        private const val CACHE_INTERVAL_MS = 5L * 60L * 1_000L
        private const val BURST_DURATION_MS = 30L * 1_000L
    }
}
