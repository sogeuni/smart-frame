package com.sogn.smartframe

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        wakeScreen(context, "lock-now-test")
    }

    companion object {
        private const val REQUEST_CODE = 1001
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 5_000L
        private const val TAG = "FrameWakeReceiver"

        @Suppress("DEPRECATION")
        fun wakeScreen(context: Context, reason: String) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "${context.packageName}:$reason",
            )
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
            Log.i(TAG, "Requested screen wake: $reason")
        }

        fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, WakeReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
