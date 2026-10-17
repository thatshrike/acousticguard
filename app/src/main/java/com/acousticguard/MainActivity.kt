package com.acousticguard

import android.Manifest
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
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var pinInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var endpointInput: EditText
    private lateinit var quietAlertSwitch: Switch
    private lateinit var communityMeshSwitch: Switch
    private lateinit var operationStatus: TextView
    private var permissionIndex = 0
    private var telemetryReceiverRegistered = false

    private val telemetryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AcousticCaptureService.ACTION_AUDIO_TELEMETRY) return
            val db = intent.getIntExtra(AcousticCaptureService.EXTRA_RMS_DB, -100)
            val candidate = intent.getBooleanExtra(AcousticCaptureService.EXTRA_CANDIDATE_FLAG, false)
            val fastTrack = intent.getBooleanExtra(AcousticCaptureService.EXTRA_FAST_TRACK, false)
            val filled = ((db + 60) * 10 / 60).coerceIn(0, 10)
            val meter = "█".repeat(filled) + "░".repeat(10 - filled)
            status.text = if (candidate) {
                "Acoustic Anomaly Flagged!\nMic Level: $db dB [$meter]${if (fastTrack) "\nFast-track fusion active" else ""}"
            } else {
                "Mic Level: $db dB [$meter]"
            }
            status.setTextColor(if (candidate) 0xFFB3261E.toInt() else 0xFF333333.toInt())
        }
    }

    private val requiredPermissions: List<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (DispatchPreferences(this@MainActivity).phoneNumber.isNotEmpty()) {
                add(Manifest.permission.SEND_SMS)
            }
            if (communityMeshSwitch.isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                    add(Manifest.permission.BLUETOOTH_ADVERTISE)
                } else {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dispatch = DispatchPreferences(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        fun add(view: android.view.View) = root.addView(
            view,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        add(TextView(this).apply {
            text = "AcousticGuard\nOn-device distress monitoring"
            textSize = 22f
        })
        status = TextView(this).apply {
            text = "Mic Level: -- dB [░░░░░░░░░░]\nWaiting for monitoring telemetry..."
            textSize = 16f
        }
        add(status)
        operationStatus = TextView(this).apply { text = "Monitoring inactive." }
        add(operationStatus)
        pinInput = EditText(this).apply {
            hint = "Quick cancel PIN (4–8 digits)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setText(dispatch.quickPin)
        }
        add(pinInput)
        phoneInput = EditText(this).apply {
            hint = "Optional emergency SMS number"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(dispatch.phoneNumber)
        }
        add(phoneInput)
        endpointInput = EditText(this).apply {
            hint = "Optional HTTPS dispatch endpoint"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(dispatch.httpsEndpoint)
        }
        add(endpointInput)
        quietAlertSwitch = Switch(this).apply {
            text = "Quiet alert mode (suppresses screen; dispatches via silent notification)"
            isChecked = dispatch.quietAlertMode
        }
        add(quietAlertSwitch)
        communityMeshSwitch = Switch(this).apply {
            text = "Enable encrypted nearby community mesh (optional)"
            isChecked = dispatch.communityMeshEnabled
        }
        add(communityMeshSwitch)

        add(Button(this).apply {
            text = "Save emergency settings"
            setOnClickListener {
                dispatch.quickPin = pinInput.text.toString()
                dispatch.phoneNumber = phoneInput.text.toString()
                dispatch.httpsEndpoint = endpointInput.text.toString()
                dispatch.quietAlertMode = quietAlertSwitch.isChecked
                dispatch.communityMeshEnabled = communityMeshSwitch.isChecked
                operationStatus.text = "Settings saved locally. Raw audio is not stored."
            }
        })
        add(Button(this).apply {
            text = "Start on-device monitoring"
            setOnClickListener { beginPermissionFlow() }
        })
        add(Button(this).apply {
            text = "TEST SOS PIPELINE (SIMULATE DISTRESS)"
            setBackgroundColor(0xFFB3261E.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                operationStatus.text = "Simulation requested; opening the pre-alert flow..."
                val intent = Intent(this@MainActivity, AcousticCaptureService::class.java).apply {
                    action = AcousticCaptureService.ACTION_SIMULATE_ALERT
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        })
        add(Button(this).apply {
            text = "Allow battery optimization exemption"
            setOnClickListener { requestBatteryOptimizationExemption() }
        })
        add(Button(this).apply {
            text = "Allow exact alarm dispatch"
            setOnClickListener {
                if (ExactAlarmScheduler.canSchedule(this@MainActivity)) {
                    operationStatus.text = "Exact alarms are enabled."
                } else {
                    ExactAlarmScheduler.requestAccess(this@MainActivity)
                }
            }
        })
        add(Button(this).apply {
            text = "Allow countdown overlay"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        })

        val scroll = ScrollView(this).apply {
            addView(
                root,
                ViewGroup.LayoutParams(
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
        val filter = IntentFilter(AcousticCaptureService.ACTION_AUDIO_TELEMETRY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(telemetryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION") registerReceiver(telemetryReceiver, filter)
        }
        telemetryReceiverRegistered = true
    }

    override fun onStop() {
        if (telemetryReceiverRegistered) {
            unregisterReceiver(telemetryReceiver)
            telemetryReceiverRegistered = false
        }
        super.onStop()
    }

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
            operationStatus.text = "Permission denied: ${permissions.firstOrNull() ?: "required permission"}. Monitoring remains off."
        }
    }

    private fun startMonitoring() {
        if (!Settings.canDrawOverlays(this)) {
            operationStatus.text = "Monitoring can start, but enable countdown overlay for the most reliable pre-alert."
        }
        if (!ExactAlarmScheduler.canSchedule(this)) {
            operationStatus.append(" Enable exact alarm access for Doze-resistant dispatch.")
        }
        val intent = Intent(this, AcousticCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        operationStatus.text = "Monitoring active. A visible foreground notification is required by Android."
    }

    private fun requestBatteryOptimizationExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            operationStatus.text = "Battery optimization exemption is already enabled."
            return
        }
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

    companion object {
        private const val REQUEST_PERMISSION = 4001
    }
}
