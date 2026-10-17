package com.acousticguard

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Best-effort process-isolation watchdog. This service stays in the default app
 * process while AcousticCaptureService runs in :remote. Android/OEM policy can
 * still kill both processes or reject a background restart; no app can promise
 * an immortal process on Android.
 */
class WatchdogService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val restarting = AtomicBoolean(false)
    private var remoteMessenger: Messenger? = null
    private var lastPongAt = 0L
    private var bound = false

    private val replyMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what == MESSAGE_PONG) lastPongAt = android.os.SystemClock.elapsedRealtime()
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remoteMessenger = service?.let(::Messenger)
            lastPongAt = android.os.SystemClock.elapsedRealtime()
            restarting.set(false)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteMessenger = null
            scheduleRemoteRestart("service disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            remoteMessenger = null
            scheduleRemoteRestart("binding died")
        }

        override fun onNullBinding(name: ComponentName?) {
            remoteMessenger = null
            scheduleRemoteRestart("null binding")
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            val messenger = remoteMessenger
            val now = android.os.SystemClock.elapsedRealtime()
            if (messenger == null || now - lastPongAt > HEARTBEAT_TIMEOUT_MS) {
                scheduleRemoteRestart("heartbeat timeout")
            } else {
                runCatching {
                    messenger.send(Message.obtain(null, MESSAGE_PING).apply { replyTo = replyMessenger })
                }.onFailure { scheduleRemoteRestart("heartbeat send failed") }
            }
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler.post(heartbeat)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRemote()
            stopSelf()
            return START_NOT_STICKY
        }
        ensureRemoteStarted()
        return START_STICKY
    }

    private fun ensureRemoteStarted() {
        val intent = Intent(this, AcousticCaptureService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }.onFailure { Log.w(TAG, "Remote service start rejected: ${it.message}") }
        if (!bound) {
            bound = runCatching {
                bindService(intent, connection, BIND_AUTO_CREATE or BIND_IMPORTANT)
            }.getOrDefault(false)
        }
    }

    private fun scheduleRemoteRestart(reason: String) {
        if (!restarting.compareAndSet(false, true)) return
        Log.w(TAG, "Restarting remote capture service: $reason")
        if (bound) {
            runCatching { unbindService(connection) }
            bound = false
        }
        handler.postDelayed({
            restarting.set(false)
            ensureRemoteStarted()
        }, RESTART_DELAY_MS)
    }

    private fun stopRemote() {
        val remote = Intent(this, AcousticCaptureService::class.java).setAction(AcousticCaptureService.ACTION_STOP)
        runCatching { startService(remote) }
        if (bound) runCatching { unbindService(connection) }
        bound = false
        stopService(Intent(this, AcousticCaptureService::class.java))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (bound) runCatching { unbindService(connection) }
        bound = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.acousticguard.action.STOP_WATCHDOG"
        const val MESSAGE_PING = 1
        const val MESSAGE_PONG = 2
        private const val HEARTBEAT_INTERVAL_MS = 2_000L
        private const val HEARTBEAT_TIMEOUT_MS = 5_000L
        private const val RESTART_DELAY_MS = 750L
        private const val TAG = "AcousticGuardWatchdog"

        fun start(context: android.content.Context) {
            val intent = Intent(context, WatchdogService::class.java)
            // This watchdog is intentionally a regular service in the UI process;
            // the remote microphone service owns the required microphone FGS.
            context.startService(intent)
        }

        fun stop(context: android.content.Context) {
            runCatching {
                context.startService(Intent(context, WatchdogService::class.java).setAction(ACTION_STOP))
            }
            context.stopService(Intent(context, WatchdogService::class.java))
        }
    }
}
