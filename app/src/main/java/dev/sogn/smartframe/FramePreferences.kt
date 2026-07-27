package dev.sogn.smartframe

import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri

data class FrameConfig(
    val url: String = DEFAULT_URL,
    val startMinutes: Int = DEFAULT_START_MINUTES,
    val endMinutes: Int = DEFAULT_END_MINUTES,
) {
    companion object {
        const val DEFAULT_URL = "https://demo.immichkiosk.app"
        const val DEFAULT_START_MINUTES = 7 * 60
        const val DEFAULT_END_MINUTES = 21 * 60
    }
}

object FramePreferences {
    private const val PREFERENCES_NAME = "frame_settings"
    private const val KEY_URL = "url"
    private const val KEY_START_MINUTES = "start_minutes"
    private const val KEY_END_MINUTES = "end_minutes"

    fun load(context: Context): FrameConfig {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        return FrameConfig(
            url = preferences.getString(KEY_URL, FrameConfig.DEFAULT_URL)
                ?: FrameConfig.DEFAULT_URL,
            startMinutes = preferences.getInt(
                KEY_START_MINUTES,
                FrameConfig.DEFAULT_START_MINUTES,
            ),
            endMinutes = preferences.getInt(
                KEY_END_MINUTES,
                FrameConfig.DEFAULT_END_MINUTES,
            ),
        )
    }

    fun save(context: Context, config: FrameConfig) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_URL, config.url)
                    .putInt(KEY_START_MINUTES, config.startMinutes)
                    .putInt(KEY_END_MINUTES, config.endMinutes)
            }
    }

    fun normalizeUrl(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = candidate.toUri()
        return candidate.takeIf {
            uri.scheme.equals("https", true) &&
                !uri.host.isNullOrBlank()
        }
    }

    fun permissionState(context: Context): FramePermissionState {
        val devicePolicyManager =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val adminComponent = ComponentName(context, ScreenOffAdminReceiver::class.java)
        return FramePermissionState(
            deviceAdminSupported =
                context.packageManager.hasSystemFeature("android.software.device_admin"),
            deviceAdminActive = devicePolicyManager.isAdminActive(adminComponent),
            overlayGranted = Settings.canDrawOverlays(context),
            exactAlarmGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms(),
        )
    }

    fun isReady(context: Context): Boolean {
        val config = load(context)
        val permissions = permissionState(context)
        return normalizeUrl(config.url) != null &&
            config.startMinutes != config.endMinutes &&
            permissions.deviceAdminSupported &&
            permissions.deviceAdminActive &&
            permissions.overlayGranted &&
            permissions.exactAlarmGranted
    }
}

data class FramePermissionState(
    val deviceAdminSupported: Boolean = true,
    val deviceAdminActive: Boolean = false,
    val overlayGranted: Boolean = false,
    val exactAlarmGranted: Boolean = true,
) {
    val allRequiredGranted: Boolean
        get() = deviceAdminSupported &&
            deviceAdminActive &&
            overlayGranted &&
            exactAlarmGranted
}
