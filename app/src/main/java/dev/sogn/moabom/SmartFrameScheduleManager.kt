package dev.sogn.moabom

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object SmartFrameScheduleManager {
    private const val ACTION_START = "dev.sogn.moabom.action.SCHEDULE_START"
    private const val ACTION_END = "dev.sogn.moabom.action.SCHEDULE_END"
    private const val START_REQUEST_CODE = 2001
    private const val END_REQUEST_CODE = 2002
    private const val TAG = "SmartFrameSchedule"

    fun sync(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        val startIntent = pendingIntent(appContext, ACTION_START, START_REQUEST_CODE)
        val endIntent = pendingIntent(appContext, ACTION_END, END_REQUEST_CODE)
        alarmManager.cancel(startIntent)
        alarmManager.cancel(endIntent)

        val config = SmartFramePreferences.load(appContext)
        if (!config.scheduleEnabled) {
            Log.i(TAG, "Schedule disabled; existing alarms canceled")
            return
        }
        if (!SmartFramePreferences.isReady(appContext)) {
            Log.i(TAG, "Schedule not registered because setup is incomplete")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            Log.w(TAG, "Schedule not registered because exact alarms are not allowed")
            return
        }

        try {
            scheduleExact(
                alarmManager,
                SmartFrameSchedule.nextOccurrenceMillis(
                    System.currentTimeMillis(),
                    config.startMinutes,
                ),
                startIntent,
            )
            scheduleExact(
                alarmManager,
                SmartFrameSchedule.nextOccurrenceMillis(
                    System.currentTimeMillis(),
                    config.endMinutes,
                ),
                endIntent,
            )
            Log.i(
                TAG,
                "Daily schedule registered: ${config.startMinutes} -> ${config.endMinutes}",
            )
        } catch (error: SecurityException) {
            alarmManager.cancel(startIntent)
            alarmManager.cancel(endIntent)
            Log.e(TAG, "Failed to register exact schedule", error)
        }
    }

    fun isDisplayTime(context: Context): Boolean {
        val config = SmartFramePreferences.load(context)
        return SmartFrameSchedule.shouldDisplay(
            config.scheduleEnabled,
            SmartFrameSchedule.currentMinutes(),
            config.startMinutes,
            config.endMinutes,
        )
    }

    fun applyCurrentState(context: Context) {
        if (!SmartFramePreferences.load(context).scheduleEnabled) return
        if (!SmartFramePreferences.isReady(context)) return
        if (isDisplayTime(context)) {
            wakeAndOpenDisplay(context)
        } else {
            turnScreenOff(context)
        }
    }

    fun handleAlarm(context: Context, action: String?) {
        sync(context)
        if (!SmartFramePreferences.load(context).scheduleEnabled) return
        if (!SmartFramePreferences.isReady(context)) return
        when (action) {
            ACTION_START -> wakeAndOpenDisplay(context)
            ACTION_END -> turnScreenOff(context)
        }
    }

    fun turnScreenOff(context: Context) {
        val devicePolicyManager =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, ScreenOffAdminReceiver::class.java)
        if (!devicePolicyManager.isAdminActive(admin)) {
            Log.w(TAG, "Screen-off skipped because device admin is not active")
            return
        }
        try {
            devicePolicyManager.lockNow()
            Log.i(TAG, "Screen turned off for inactive display schedule")
        } catch (error: SecurityException) {
            Log.e(TAG, "Failed to turn screen off", error)
        }
    }

    private fun wakeAndOpenDisplay(context: Context) {
        WakeReceiver.wakeScreen(context, "daily-schedule")
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                },
            )
            Log.i(TAG, "Start time reached; Smart Frame display requested")
        } catch (error: RuntimeException) {
            Log.e(TAG, "Failed to open Smart Frame display", error)
        }
    }

    private fun scheduleExact(
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        operation: PendingIntent,
    ) {
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            operation,
        )
    }

    private fun pendingIntent(
        context: Context,
        action: String,
        requestCode: Int,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ScheduleAlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
