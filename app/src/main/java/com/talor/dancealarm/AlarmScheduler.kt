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

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (!Prefs.isEnabled(c)) {
            am.cancel(firePending(c))
            return
        }
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(nextTrigger(c), showPending(c)), firePending(c))
        } catch (e: SecurityException) {
            Log.e("DanceAlarm", "Exact alarm permission missing", e)
        }
    }
}
