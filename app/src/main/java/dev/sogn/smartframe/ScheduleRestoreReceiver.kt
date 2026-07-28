package dev.sogn.smartframe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScheduleRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> SmartFrameScheduleManager.sync(context)

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> {
                SmartFrameScheduleManager.sync(context)
                SmartFrameScheduleManager.applyCurrentState(context)
            }
        }
    }
}
