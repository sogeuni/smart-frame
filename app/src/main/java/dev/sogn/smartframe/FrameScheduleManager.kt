package dev.sogn.smartframe

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object FrameScheduleManager {
    private const val ACTION_START = "dev.sogn.smartframe.action.SCHEDULE_START"
    private const val ACTION_END = "dev.sogn.smartframe.action.SCHEDULE_END"
    private const val START_REQUEST_CODE = 2001
    private const val END_REQUEST_CODE = 2002
    private const val TAG = "FrameSchedule"

    fun sync(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        val startIntent = pendingIntent(appContext, ACTION_START, START_REQUEST_CODE)
        val endIntent = pendingIntent(appContext, ACTION_END, END_REQUEST_CODE)
        alarmManager.cancel(startIntent)
        alarmManager.cancel(endIntent)

        if (!FramePreferences.isReady(appContext)) {
            Log.i(TAG, "Schedule not registered because setup is incomplete")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            Log.w(TAG, "Schedule not registered because exact alarms are not allowed")
            return
        }

        val config = FramePreferences.load(appContext)
        try {
            scheduleExact(
                alarmManager,
                FrameSchedule.nextOccurrenceMillis(
                    System.currentTimeMillis(),
                    config.startMinutes,
                ),
                startIntent,
            )
            scheduleExact(
                alarmManager,
                FrameSchedule.nextOccurrenceMillis(
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
        val config = FramePreferences.load(context)
        return FrameSchedule.isDisplayTime(
            FrameSchedule.currentMinutes(),
            config.startMinutes,
            config.endMinutes,
        )
    }

    fun applyCurrentState(context: Context) {
        if (!FramePreferences.isReady(context)) return
        if (isDisplayTime(context)) {
            wakeAndOpenDisplay(context)
        } else {
            turnScreenOff(context)
        }
    }

    fun handleAlarm(context: Context, action: String?) {
        sync(context)
        if (!FramePreferences.isReady(context)) return
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
            Log.i(TAG, "End time reached; screen turned off")
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
