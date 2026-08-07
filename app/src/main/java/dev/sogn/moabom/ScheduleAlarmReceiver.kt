package dev.sogn.moabom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SmartFrameScheduleManager.handleAlarm(context, intent.action)
    }
}
