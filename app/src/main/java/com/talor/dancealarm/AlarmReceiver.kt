package com.talor.dancealarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AlarmScheduler.ACTION_FIRE) {
            AlarmService.start(context)
            AlarmScheduler.schedule(context) // queue the next one
        }
    }
}

/** Re-arms the alarm after reboot, app update, clock changes, or when the phone's alarm changes. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // In "own time" mode our setAlarmClock() itself changes the next alarm clock;
        // ignore that broadcast there so we don't loop.
        if (intent.action == AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED && !Prefs.followPhone(context)) return
        AlarmScheduler.schedule(context)
    }
}
