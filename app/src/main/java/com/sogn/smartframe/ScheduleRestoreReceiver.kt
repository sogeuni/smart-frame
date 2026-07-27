package com.sogn.smartframe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScheduleRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FrameScheduleManager.sync(context)
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            FrameScheduleManager.applyCurrentState(context)
        }
    }
}
