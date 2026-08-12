package com.jaemin.ddocdocmacro

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object AlarmScheduler {
    private const val REQUEST_RUN = 7001
    private const val REQUEST_SHOW = 7002

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java)
        return manager.canScheduleExactAlarms()
    }

    fun scheduleNext(context: Context): Long? {
        if (!Prefs.enabled(context) || !canScheduleExact(context)) return null
        val triggerAt = computeNextTrigger(context) ?: return null
        val alarmManager = context.getSystemService(AlarmManager::class.java)

        val operation = PendingIntent.getActivity(
            context,
            REQUEST_RUN,
            Intent(context, AlarmLaunchActivity::class.java).apply {
                action = "com.jaemin.ddocdocmacro.RUN_ALARM"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = PendingIntent.getActivity(
            context,
            REQUEST_SHOW,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return runCatching {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), operation)
            triggerAt
        }.getOrNull()
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getActivity(
            context,
            REQUEST_RUN,
            Intent(context, AlarmLaunchActivity::class.java).apply {
                action = "com.jaemin.ddocdocmacro.RUN_ALARM"
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pending != null) alarmManager.cancel(pending)
    }

    fun computeNextTrigger(context: Context, nowMillis: Long = System.currentTimeMillis()): Long? {
        val mask = Prefs.daysMask(context)
        if (mask == 0) return null
        val time = runCatching { TimeUtils.parseLocalTime(Prefs.time(context)) }.getOrNull() ?: return null
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val today = LocalDate.now(zone)

        for (offset in 0..7) {
            val date = today.plusDays(offset.toLong())
            val bit = date.dayOfWeek.value - 1
            if (mask and (1 shl bit) == 0) continue
            val candidate = LocalDateTime.of(date, time)
            if (candidate.isAfter(now.plusSeconds(1))) {
                return candidate.atZone(zone).toInstant().toEpochMilli()
            }
        }
        return null
    }

    fun format(millis: Long): String {
        val formatter = DateTimeFormatter.ofPattern("M월 d일(E) HH:mm:ss.SSS")
        return Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .format(formatter)
    }
}
