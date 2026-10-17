package com.acousticguard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/** Observes connectivity without owning a scheduler or persisting raw data. */
class SosConnectivityMonitor(
    context: Context,
    private val onAvailable: () -> Unit
) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onAvailable()
    }

    fun start(): Boolean {
        if (registered || manager == null) return registered
        return runCatching {
            manager.registerDefaultNetworkCallback(callback)
            registered = true
            true
        }.getOrDefault(false)
    }

    fun stop() {
        if (!registered) return
        runCatching { manager?.unregisterNetworkCallback(callback) }
        registered = false
    }
}
