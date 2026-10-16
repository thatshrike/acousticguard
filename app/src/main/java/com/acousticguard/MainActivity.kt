package com.acousticguard

import android.Manifest
import android.app.Activity
import android.content.Intent
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
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var pinInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var endpointInput: EditText
    private var permissionIndex = 0

    private val requiredPermissions: List<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (DispatchPreferences(this@MainActivity).phoneNumber.isNotEmpty()) {
                add(Manifest.permission.SEND_SMS)
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
        status = TextView(this).apply { text = "No microphone is active." }
        add(status)
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

        add(Button(this).apply {
            text = "Save emergency settings"
            setOnClickListener {
                dispatch.quickPin = pinInput.text.toString()
                dispatch.phoneNumber = phoneInput.text.toString()
                dispatch.httpsEndpoint = endpointInput.text.toString()
                status.text = "Settings saved locally. Raw audio is not stored."
            }
        })
        add(Button(this).apply {
            text = "Start on-device monitoring"
            setOnClickListener { beginPermissionFlow() }
        })
        add(Button(this).apply {
            text = "Allow battery optimization exemption"
            setOnClickListener { requestBatteryOptimizationExemption() }
        })
        add(Button(this).apply {
            text = "Allow exact alarm dispatch"
            setOnClickListener {
                if (ExactAlarmScheduler.canSchedule(this@MainActivity)) {
                    status.text = "Exact alarms are enabled."
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
            status.text = "Permission denied: ${permissions.firstOrNull() ?: "required permission"}. Monitoring remains off."
        }
    }

    private fun startMonitoring() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "Monitoring can start, but enable countdown overlay for the most reliable pre-alert."
        }
        if (!ExactAlarmScheduler.canSchedule(this)) {
            status.append(" Enable exact alarm access for Doze-resistant dispatch.")
        }
        val intent = Intent(this, AcousticCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        status.text = "Monitoring active. A visible foreground notification is required by Android."
    }

    private fun requestBatteryOptimizationExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            status.text = "Battery optimization exemption is already enabled."
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
