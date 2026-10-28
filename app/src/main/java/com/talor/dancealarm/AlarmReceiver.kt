package com.talor.dancealarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AlarmScheduler.ACTION_FIRE) {
            AlarmService.start(context)
            AlarmScheduler.schedule(context) // queue tomorrow's alarm
        }
    }
}

/** Re-arms the alarm after reboot, app update, or clock/timezone changes. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.schedule(context)
    }
}
