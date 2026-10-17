package com.acousticguard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

object ExactAlarmScheduler {
    const val EXTRA_EVENT_ID = "event_id"
    const val EXTRA_LABELS = "labels"
    const val ACTION_DISPATCH = "com.acousticguard.action.DISPATCH_SOS"

    fun canSchedule(context: Context): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    }

    fun requestAccess(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    }

    fun schedule(context: Context, eventId: String, labels: Array<String>, delayMs: Long = 1_000L): Boolean {
        require(delayMs >= 0L) { "delayMs must be non-negative" }
        if (!canSchedule(context)) return false
        if (!DispatchEventStore(context).markScheduled(eventId)) return false
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, SosDispatchReceiver::class.java).apply {
            action = ACTION_DISPATCH
            data = android.net.Uri.parse("acousticguard://sos/$eventId")
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_LABELS, labels)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return runCatching {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pendingIntent
            )
            true
        }.getOrElse {
            DispatchEventStore(context).markFailed(eventId)
            false
        }
    }

    fun cancel(context: Context, eventId: String) {
        DispatchEventStore(context).markCanceled(eventId)
        val intent = Intent(context, SosDispatchReceiver::class.java).apply {
            action = ACTION_DISPATCH
            data = android.net.Uri.parse("acousticguard://sos/$eventId")
        }
        PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )?.let { context.getSystemService(AlarmManager::class.java).cancel(it) }
    }
}
