package com.talor.dancealarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

object AlarmScheduler {
    const val ACTION_FIRE = "com.talor.dancealarm.FIRE"

    /** Ring this long after the phone's own alarm, so the dance screen ends up on top. */
    private const val FOLLOW_DELAY_MS = 5_000L

    /** Which hours count as a "morning" alarm when following the phone's alarm. */
    private val MORNING_HOURS = 3..11

    fun nextTrigger(c: Context): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, Prefs.hour(c))
            set(Calendar.MINUTE, Prefs.minute(c))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Small buffer so re-scheduling right after the alarm fires picks tomorrow.
        if (cal.timeInMillis <= System.currentTimeMillis() + 30_000) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun firePending(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 0,
        Intent(c, AlarmReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun showPending(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 1,
        Intent(c, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun canScheduleExact(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    /**
     * The next morning alarm set in another app (e.g. the Clock app), or null.
     * Android only exposes the single soonest alarm clock, so if that one isn't in the
     * morning we wait — once it fires, the system tells us about the next one.
     */
    fun phoneMorningAlarm(c: Context): Long? {
        val am = c.getSystemService(AlarmManager::class.java)
        val info = am.nextAlarmClock ?: return null
        if (info.showIntent?.creatorPackage == c.packageName) return null
        if (info.triggerTime <= System.currentTimeMillis()) return null
        val hour = Calendar.getInstance().apply { timeInMillis = info.triggerTime }.get(Calendar.HOUR_OF_DAY)
        return if (hour in MORNING_HOURS) info.triggerTime else null
    }

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = firePending(c)
        if (!Prefs.isEnabled(c)) {
            am.cancel(pi)
            Prefs.setScheduledAt(c, 0)
            return
        }
        try {
            if (Prefs.followPhone(c)) {
                // Our own alarm must not be an "alarm clock" here, otherwise it would hide the
                // phone's alarm from getNextAlarmClock(). Exact + allow-while-idle still wakes the phone.
                am.cancel(pi)
                val phone = phoneMorningAlarm(c)
                if (phone == null) {
                    Prefs.setScheduledAt(c, 0)
                    return
                }
                val at = phone + FOLLOW_DELAY_MS
                if (canScheduleExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                Prefs.setScheduledAt(c, at)
            } else {
                val at = nextTrigger(c)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showPending(c)), pi)
                Prefs.setScheduledAt(c, at)
            }
        } catch (e: SecurityException) {
            Log.e("DanceAlarm", "Exact alarm permission missing", e)
        }
    }
}
