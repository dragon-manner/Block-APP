package com.blockapp.android.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.blockapp.android.usage.UsageArchiveReceiver
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

object AlarmScheduler {
    /**
     * Arms the once-a-day UsageArchiveReceiver alarm, a little before midnight so it catches
     * nearly the whole day. Inexact and non-waking on purpose: nothing about it is urgent, and
     * a late or batched run loses nothing (UsageArchive replays the last week each time). Safe to
     * call repeatedly, since FLAG_UPDATE_CURRENT replaces the existing alarm. Called on every
     * process start and on boot, which also re-arms it after a force-stop cancels it.
     */
    fun scheduleDailyArchive(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            DAILY_ARCHIVE_REQUEST_CODE,
            Intent(context, UsageArchiveReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val zone = ZoneId.systemDefault()
        var first = LocalDate.now(zone).atTime(DAILY_ARCHIVE_TIME).atZone(zone).toInstant().toEpochMilli()
        if (first <= System.currentTimeMillis()) first += AlarmManager.INTERVAL_DAY
        alarmManager.setInexactRepeating(AlarmManager.RTC, first, AlarmManager.INTERVAL_DAY, pendingIntent)
    }

    /** Any fixed code works: this is the only alarm the app sets. */
    private const val DAILY_ARCHIVE_REQUEST_CODE = 0x0A2C
    private val DAILY_ARCHIVE_TIME: LocalTime = LocalTime.of(23, 40)
}
