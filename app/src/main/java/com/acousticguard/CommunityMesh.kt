package com.acousticguard

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

object CommunityMeshBroadcaster {
    val SERVICE_UUID: UUID = UUID.fromString("3f9d6c2a-49d0-4b65-8a0f-0c9d6b9a0101")
    private const val LEGACY_SERVICE_PAYLOAD_LIMIT = 20
    private val handler = Handler(Looper.getMainLooper())
    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertiseCallback? = null
    private var stopRunnable: Runnable? = null

    @Synchronized
    fun start(context: Context, encryptedPayload: ByteArray, durationMs: Long = 30_000L): Boolean {
        if (encryptedPayload.isEmpty() || encryptedPayload.size > 128) return false
        if (!hasPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
        ) return false
        val adapter = bluetoothAdapter(context) ?: return false
        if (!adapter.isEnabled) return false
        val leAdvertiser = adapter.bluetoothLeAdvertiser ?: return false
        val extended = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && adapter.isLeExtendedAdvertisingSupported
        if (!extended && encryptedPayload.size > LEGACY_SERVICE_PAYLOAD_LIMIT) return false

        stop()
        val settingsBuilder = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(false)
            .setTimeout(0)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), encryptedPayload)
            .build()
        val newCallback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "BLE advertisement failed: $errorCode")
                synchronized(this@CommunityMeshBroadcaster) {
                    advertiser = null
                    callback = null
                }
            }
        }
        return runCatching {
            leAdvertiser.startAdvertising(settingsBuilder.build(), data, newCallback)
            advertiser = leAdvertiser
            callback = newCallback
            val scheduledStop = Runnable { stop() }
            stopRunnable = scheduledStop
            handler.postDelayed(scheduledStop, durationMs.coerceIn(1_000L, 120_000L))
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun stop() {
        stopRunnable?.let(handler::removeCallbacks)
        stopRunnable = null
        val currentAdvertiser = advertiser
        val currentCallback = callback
        if (currentAdvertiser != null && currentCallback != null) {
            runCatching { currentAdvertiser.stopAdvertising(currentCallback) }
        }
        advertiser = null
        callback = null
    }

    private fun bluetoothAdapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun hasPermission(context: Context, permission: String): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private const val TAG = "AcousticGuardMesh"
}

object CommunityMeshScanner {
    private const val TAG = "AcousticGuardMesh"
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null

    @Synchronized
    fun start(context: Context, onValidPayload: (DecodedSosPayload) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (!hasPermission(context, Manifest.permission.BLUETOOTH_SCAN) ||
                !hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT))
        ) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            !hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        ) return false
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (!adapter.isEnabled) return false
        val leScanner = adapter.bluetoothLeScanner ?: return false
        stop()
        val newCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val payload = result.scanRecord?.getServiceData(ParcelUuid(CommunityMeshBroadcaster.SERVICE_UUID)) ?: return
                val decoded = runCatching { SosPayloadCrypto(context).decrypt(payload) }.getOrNull() ?: return
                onValidPayload(decoded)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed: $errorCode")
                synchronized(this@CommunityMeshScanner) {
                    scanner = null
                    callback = null
                }
            }
        }
        return runCatching {
            leScanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
                newCallback
            )
            scanner = leScanner
            callback = newCallback
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun stop() {
        val currentScanner = scanner
        val currentCallback = callback
        if (currentScanner != null && currentCallback != null) {
            runCatching { currentScanner.stopScan(currentCallback) }
        }
        scanner = null
        callback = null
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

object CommunityMeshNotifier {
    private const val CHANNEL_ID = "community_mesh_alerts"
    private const val NOTIFICATION_ID = 201

    fun show(context: Context, payload: DecodedSosPayload) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(
            android.app.NotificationChannel(
                CHANNEL_ID,
                "Nearby community alerts",
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
        )
        manager.notify(
            NOTIFICATION_ID,
            android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Nearby distress beacon detected")
                .setContentText("An authenticated AcousticGuard alert is nearby; exact distance is unavailable.")
                .setCategory(android.app.Notification.CATEGORY_ALARM)
                .setPriority(android.app.Notification.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
        )
        Log.i("AcousticGuardMesh", "Authenticated nearby event received")
    }
}
