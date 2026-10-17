package com.acousticguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var dispatch: DispatchPreferences
    private var permissionIndex = 0
    private var telemetryReceiverRegistered = false
    private var isMonitoringActive = false
    private var pendingOwnerVoiceEnrollment = false

    private val telemetryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AcousticCaptureService.ACTION_CALIBRATION_STATUS -> {
                    val count = intent.getIntExtra(AcousticCaptureService.EXTRA_CALIBRATION_COUNT, 0)
                    val remaining = intent.getIntExtra(AcousticCaptureService.EXTRA_CALIBRATION_REMAINING, 0)
                    webView.post {
                        webView.evaluateJavascript("window.updateCalibration($count, $remaining);", null)
                    }
                }
                AcousticCaptureService.ACTION_AUDIO_TELEMETRY -> {
                    val db = intent.getIntExtra(AcousticCaptureService.EXTRA_RMS_DB, -100)
                    val candidate = intent.getBooleanExtra(AcousticCaptureService.EXTRA_CANDIDATE_FLAG, false)
                    val fastTrack = intent.getBooleanExtra(AcousticCaptureService.EXTRA_FAST_TRACK, false)
                    val stage = intent.getStringExtra(AcousticCaptureService.EXTRA_PIPELINE_STAGE).orEmpty()
                    webView.post {
                        webView.evaluateJavascript("window.updateTelemetry($db, $candidate, $fastTrack);", null)
                        if (stage.isNotEmpty()) {
                            webView.evaluateJavascript("if(window.updatePipelineStage) window.updatePipelineStage(${JSONObject.quote(stage)});", null)
                        }
                    }
                }
                AcousticCaptureService.ACTION_OWNER_VOICE_STATUS -> {
                    val state = JSONObject().apply {
                        put("status", intent.getStringExtra(AcousticCaptureService.EXTRA_OWNER_VOICE_STATUS).orEmpty())
                        put("enrolled", intent.getBooleanExtra(AcousticCaptureService.EXTRA_OWNER_VOICE_ENROLLED, false))
                        put("sampleCount", intent.getIntExtra(AcousticCaptureService.EXTRA_OWNER_VOICE_SAMPLE_COUNT, 0))
                        put("remaining", intent.getIntExtra(AcousticCaptureService.EXTRA_OWNER_VOICE_REMAINING, 0))
                        put("modelAvailable", intent.getBooleanExtra(AcousticCaptureService.EXTRA_OWNER_VOICE_MODEL_AVAILABLE, false))
                        intent.getStringExtra(AcousticCaptureService.EXTRA_OWNER_VOICE_ERROR)?.let { put("error", it) }
                    }
                    webView.post {
                        webView.evaluateJavascript(
                            "if(window.updateOwnerVoiceProfile) window.updateOwnerVoiceProfile($state);",
                            null
                        )
                    }
                }
            }
        }
    }

    private val requiredPermissions: List<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (dispatch.phoneNumber.isNotEmpty()) {
                add(Manifest.permission.SEND_SMS)
            }
            if (dispatch.communityMeshEnabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                    add(Manifest.permission.BLUETOOTH_ADVERTISE)
                } else {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dispatch = DispatchPreferences(this)

        webView = WebView(this).apply {
            setBackgroundColor(0xFF08090D.toInt())
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    syncInitialStateToWeb()
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(AcousticGuardBridge(), "AndroidBridge")
            loadUrl("file:///android_asset/web/index.html")
        }

        // Attached main activity layout with scroll frame container
        val scroll = FrameLayout(this).apply {
            setBackgroundColor(0xFF08090D.toInt())
            addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        setContentView(scroll)
    }

    override fun onStart() {
        super.onStart()
        if (telemetryReceiverRegistered) return
        val filter = IntentFilter(AcousticCaptureService.ACTION_AUDIO_TELEMETRY).apply {
            addAction(AcousticCaptureService.ACTION_CALIBRATION_STATUS)
            addAction(AcousticCaptureService.ACTION_OWNER_VOICE_STATUS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(telemetryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION") registerReceiver(telemetryReceiver, filter)
        }
        telemetryReceiverRegistered = true
    }

    override fun onResume() {
        super.onResume()
        syncInitialStateToWeb()
    }

    override fun onStop() {
        if (telemetryReceiverRegistered) {
            unregisterReceiver(telemetryReceiver)
            telemetryReceiverRegistered = false
        }
        super.onStop()
    }

    private fun syncInitialStateToWeb() {
        val stateJson = getSerializedState()
        webView.post {
            webView.evaluateJavascript("if(window.initFromNative) window.initFromNative($stateJson);", null)
        }
    }

    private fun getSerializedState(): String {
        val state = JSONObject().apply {
            put("isMonitoring", isMonitoringActive)
            put("quietAlertMode", dispatch.quietAlertMode)
            put("communityMeshEnabled", dispatch.communityMeshEnabled)
            put("quickPin", dispatch.quickPin)
            put("phoneNumber", dispatch.phoneNumber)
            put("httpsEndpoint", dispatch.httpsEndpoint)
            put("safeSamplesCount", PersonalizationStore(this@MainActivity).sampleCount())
            put("hasOverlay", Settings.canDrawOverlays(this@MainActivity))
            put("hasExactAlarm", ExactAlarmScheduler.canSchedule(this@MainActivity))
            put("isIgnoringBattery", getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
            put("keyVersion", SosPayloadCrypto(this@MainActivity).currentKeyVersion())
            put(
                "nativeAudioStatus",
                if (BuildConfig.NATIVE_AUDIO_ENABLED) NativeAudioBoundary.status().detail else "AudioRecord 16kHz"
            )
            val ownerVoice = EncryptedOwnerVoiceProfileStore(this@MainActivity).status()
            put("ownerVoiceProfile", JSONObject().apply {
                put("status", if (ownerVoice.enrolled) "enrolled" else "idle")
                put("enrolled", ownerVoice.enrolled)
                put("sampleCount", ownerVoice.sampleCount)
                put("modelAvailable", ownerVoiceModelAvailable())
            })
        }
        return state.toString()
    }

    private fun ownerVoiceModelAvailable(): Boolean = runCatching {
        assets.open(TfliteVoiceEmbeddingModel.DEFAULT_ASSET_NAME).use { true }
    }.getOrDefault(false)

    private fun beginPermissionFlow() {
        permissionIndex = 0
        requestNextPermission()
    }

    private fun requestNextPermission() {
        val permissions = requiredPermissions
        while (permissionIndex < permissions.size && checkSelfPermission(permissions[permissionIndex]) == PackageManager.PERMISSION_GRANTED) {
            permissionIndex++
        }
        if (permissionIndex == permissions.size) {
            startMonitoring()
        } else {
            requestPermissions(arrayOf(permissions[permissionIndex]), REQUEST_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            permissionIndex++
            requestNextPermission()
        } else {
            isMonitoringActive = false
            syncInitialStateToWeb()
        }
    }

    private fun startMonitoring() {
        WatchdogService.start(this)
        val intent = Intent(this, AcousticCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isMonitoringActive = true
        syncInitialStateToWeb()
        if (pendingOwnerVoiceEnrollment) {
            pendingOwnerVoiceEnrollment = false
            startOwnerVoiceEnrollmentService()
        }
    }

    private fun startOwnerVoiceEnrollmentService() {
        val intent = Intent(this, AcousticCaptureService::class.java).apply {
            action = AcousticCaptureService.ACTION_START_OWNER_VOICE_ENROLLMENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isMonitoringActive = true
        syncInitialStateToWeb()
    }

    private fun stopMonitoring() {
        WatchdogService.stop(this)
        val stopIntent = Intent(this, AcousticCaptureService::class.java).apply {
            action = AcousticCaptureService.ACTION_STOP
        }
        startService(stopIntent)
        stopService(Intent(this, AcousticCaptureService::class.java))
        isMonitoringActive = false
        syncInitialStateToWeb()
    }

    // TEST SOS PIPELINE (SIMULATE DISTRESS)
    private fun triggerSimulateAlert() {
        val intent = Intent(this, AcousticCaptureService::class.java).apply {
            action = AcousticCaptureService.ACTION_SIMULATE_ALERT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: SecurityException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    inner class AcousticGuardBridge {
        @JavascriptInterface
        fun toggleMonitoring() {
            runOnUiThread {
                if (isMonitoringActive) {
                    stopMonitoring()
                } else {
                    beginPermissionFlow()
                }
            }
        }

        @JavascriptInterface
        fun cancelSosAlert() {
            runOnUiThread {
                val cancelIntent = Intent(this@MainActivity, AcousticCaptureService::class.java).apply {
                    action = AcousticCaptureService.ACTION_CANCEL_ALERT
                }
                startService(cancelIntent)
            }
        }

        @JavascriptInterface
        fun simulateDistress() {
            runOnUiThread {
                // TEST SOS PIPELINE (SIMULATE DISTRESS)
                triggerSimulateAlert()
            }
        }

        @JavascriptInterface
        fun setQuietAlertMode(enabled: Boolean) {
            runOnUiThread {
                dispatch.quietAlertMode = enabled
            }
        }

        @JavascriptInterface
        fun setCommunityMeshEnabled(enabled: Boolean) {
            runOnUiThread {
                dispatch.communityMeshEnabled = enabled
                if (enabled) {
                    beginPermissionFlow()
                }
            }
        }

        @JavascriptInterface
        fun saveQuickPin(pin: String) {
            runOnUiThread {
                dispatch.quickPin = pin
            }
        }

        @JavascriptInterface
        fun saveCircleSettings(phone: String, endpoint: String) {
            runOnUiThread {
                dispatch.phoneNumber = phone
                dispatch.httpsEndpoint = endpoint
                if (phone.isNotEmpty()) {
                    beginPermissionFlow()
                }
            }
        }

        @JavascriptInterface
        fun recordSafeSample() {
            runOnUiThread {
                val intent = Intent(this@MainActivity, AcousticCaptureService::class.java).apply {
                    action = AcousticCaptureService.ACTION_RECORD_SAFE_SAMPLE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        }

        @JavascriptInterface
        fun startOwnerVoiceEnrollment() {
            runOnUiThread {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingOwnerVoiceEnrollment = true
                    beginPermissionFlow()
                } else {
                    startOwnerVoiceEnrollmentService()
                }
            }
        }

        @JavascriptInterface
        fun clearOwnerVoiceProfile() {
            runOnUiThread {
                EncryptedOwnerVoiceProfileStore(this@MainActivity).clear()
                if (isMonitoringActive) {
                    startService(
                        Intent(this@MainActivity, AcousticCaptureService::class.java).apply {
                            action = AcousticCaptureService.ACTION_CLEAR_OWNER_VOICE_PROFILE
                        }
                    )
                }
                syncInitialStateToWeb()
            }
        }

        @JavascriptInterface
        fun requestOverlay() {
            runOnUiThread {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
        }

        @JavascriptInterface
        fun requestExactAlarm() {
            runOnUiThread {
                if (!ExactAlarmScheduler.canSchedule(this@MainActivity)) {
                    ExactAlarmScheduler.requestAccess(this@MainActivity)
                }
            }
        }

        @JavascriptInterface
        fun requestBatteryExemption() {
            runOnUiThread {
                requestBatteryOptimizationExemption()
            }
        }

        @JavascriptInterface
        fun rotateKey(): Int {
            return SosPayloadCrypto(this@MainActivity).rotateKey()
        }

        @JavascriptInterface
        fun getInitialState(): String {
            return getSerializedState()
        }
    }

    companion object {
        private const val REQUEST_PERMISSION = 4001
    }
}
