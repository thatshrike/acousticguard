package com.acousticguard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

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

    fun schedule(context: Context, eventId: String, labels: Array<String>): Boolean {
        if (!canSchedule(context)) return false
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, SosDispatchReceiver::class.java).apply {
            action = ACTION_DISPATCH
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_LABELS, labels)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            eventId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 1_000L,
            pendingIntent
        )
        return true
    }

    fun cancel(context: Context, eventId: String) {
        val intent = Intent(context, SosDispatchReceiver::class.java).apply {
            action = ACTION_DISPATCH
        }
        PendingIntent.getBroadcast(
                context,
                eventId.hashCode(),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )?.let { context.getSystemService(AlarmManager::class.java).cancel(it) }
    }
}
