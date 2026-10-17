package com.acousticguard

import android.app.Activity
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.UUID

/** User-visible, ten-second confirmation window. No dispatch is scheduled until it completes. */
class GracePeriodActivity : Activity() {
    private lateinit var countdown: TextView
    private lateinit var pinInput: EditText
    private var eventId: String = UUID.randomUUID().toString()
    private var labels: Array<String> = emptyArray()
    private var resolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        eventId = savedInstanceState?.getString(KEY_EVENT_ID)
            ?: UUID.randomUUID().toString()
        val rawLabels = savedInstanceState?.getStringArray(KEY_LABELS)
            ?: intent.getStringArrayExtra(EXTRA_MATCHED_LABELS)
        labels = rawLabels?.filterNotNull()?.toTypedArray() ?: emptyArray()
        render()
        startCountdown()
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        intent?.getStringArrayExtra(EXTRA_MATCHED_LABELS)?.let {
            labels = it.filterNotNull().toTypedArray()
        }
    }

    private fun configureWindow() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
        }
        val warning = TextView(this).apply {
            text = "Possible distress detected\nNo SOS has been sent."
            textSize = 26f
            gravity = Gravity.CENTER
        }
        countdown = TextView(this).apply {
            textSize = 56f
            gravity = Gravity.CENTER
        }
        pinInput = EditText(this).apply {
            hint = "Enter cancel PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSelectAllOnFocus(true)
        }
        val cancel = Button(this).apply {
            text = "CANCEL SOS"
            setOnClickListener { cancelAsFalsePositive("cancel_button") }
        }
        val pin = Button(this).apply {
            text = "Cancel with PIN"
            setOnClickListener {
                val preferences = DispatchPreferences(this@GracePeriodActivity)
                val candidate = pinInput.text.toString()
                if (preferences.matchesRealPin(candidate)) {
                    cancelAsFalsePositive("pin")
                } else if (preferences.matchesDuressPin(candidate) || preferences.matchesConfiguredDuressPin(candidate)) {
                    triggerDuressDispatch()
                } else {
                    pinInput.error = "PIN did not match"
                }
            }
        }
        root.addView(warning)
        root.addView(countdown)
        root.addView(pinInput, LinearLayout.LayoutParams(-1, -2))
        root.addView(pin, LinearLayout.LayoutParams(-1, -2))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            root.addView(Button(this).apply {
                text = "Cancel with biometrics"
                setOnClickListener { authenticateWithBiometrics() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(cancel, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun startCountdown() {
        vibrate()
        object : CountDownTimer(COUNTDOWN_MS, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                countdown.text = ((millisUntilFinished + 999L) / 1_000L).toString()
                vibrate()
            }

            override fun onFinish() {
                if (!resolved) dispatch()
            }
        }.start()
    }

    private fun cancelAsFalsePositive(reason: String) {
        if (resolved) return
        resolved = true
        AdaptiveThresholdStore(this).recordFalsePositive()
        FalsePositiveLogger(this).recordFalsePositive(labels.toList() + reason)
        notifyServiceResolved()
        finishAndRemoveTask()
    }

    private fun triggerDuressDispatch() {
        if (resolved) return
        resolved = true
        FalsePositiveLogger(this).recordDuressTrigger(labels.toList())
        // The screen presents the same successful-abort outcome, while the
        // service schedules the encrypted SOS without another visible prompt.
        startService(android.content.Intent(this, AcousticCaptureService::class.java).apply {
            action = AcousticCaptureService.ACTION_DURESS_DISPATCH
            putExtra(AcousticCaptureService.EXTRA_DURESS_LABELS, labels)
        })
        finishAndRemoveTask()
    }

    private fun authenticateWithBiometrics() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val prompt = BiometricPrompt.Builder(this)
            .setTitle("Cancel AcousticGuard alert")
            .setSubtitle("Verify your identity to cancel this alert")
            .setDescription("Biometric verification is local to this device")
            .setNegativeButton("Keep alert active", mainExecutor) { _, _ -> }
            .build()
        val cancellation = CancellationSignal()
        prompt.authenticate(
            cancellation,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    cancelAsFalsePositive("biometric")
                }
            }
        )
    }

    private fun dispatch() {
        if (resolved) return
        resolved = true
        if (!ExactAlarmScheduler.schedule(this, eventId, labels)) {
            // No exact-alarm access means no silent fallback to an unbounded inexact alarm.
            // This is an unavailable dispatch path, not evidence that the model was wrong.
            FalsePositiveLogger(this).recordDispatchUnavailable(eventId, "exact_alarm_unavailable")
            notifyServiceResolved()
            finishAndRemoveTask()
            return
        }
        FalsePositiveLogger(this).recordDispatchScheduled(eventId, labels.toList())
        notifyServiceResolved()
        finishAndRemoveTask()
    }

    private fun notifyServiceResolved() {
        startService(
            android.content.Intent(this, AcousticCaptureService::class.java)
                .setAction(AcousticCaptureService.ACTION_PRE_ALERT_RESOLVED)
        )
    }

    private fun vibrate() {
        val vibrator = getSystemService(Vibrator::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(100L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(100L)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_EVENT_ID, eventId)
        outState.putStringArray(KEY_LABELS, labels)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_MATCHED_LABELS = "matched_labels"
        private const val KEY_EVENT_ID = "event_id"
        private const val KEY_LABELS = "labels"
        private const val COUNTDOWN_MS = 10_000L
    }
}
