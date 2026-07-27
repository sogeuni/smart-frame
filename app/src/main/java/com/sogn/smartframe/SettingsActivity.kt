package com.sogn.smartframe

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sogn.smartframe.ui.theme.FrameTheme
import java.util.Locale

class SettingsActivity : ComponentActivity() {
    private val devicePolicyManager by lazy {
        getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    private val alarmManager by lazy {
        getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }
    private val keyguardManager by lazy {
        getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    }
    private val adminComponent by lazy {
        ComponentName(this, ScreenOffAdminReceiver::class.java)
    }

    private var permissionState by mutableStateOf(FramePermissionState())
    private var statusMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val savedConfig = remember { FramePreferences.load(this) }
            FrameTheme {
                SettingsScreen(
                    initialConfig = savedConfig,
                    permissionState = permissionState,
                    statusMessage = statusMessage,
                    onSave = ::saveConfig,
                    onOpenDisplay = ::openDisplay,
                    onDeviceAdminClick = ::toggleDeviceAdmin,
                    onOverlayClick = ::requestOverlayPermission,
                    onExactAlarmClick = ::requestExactAlarmPermission,
                    onScreenOffTest = ::runScreenOffTest,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionState = FramePreferences.permissionState(this)
        FrameScheduleManager.sync(this)
    }

    private fun saveConfig(url: String, startMinutes: Int, endMinutes: Int): Boolean {
        val normalizedUrl = FramePreferences.normalizeUrl(url)
        if (normalizedUrl == null) {
            statusMessage = getString(R.string.invalid_url)
            return false
        }
        if (startMinutes == endMinutes) {
            statusMessage = getString(R.string.invalid_schedule)
            return false
        }
        FramePreferences.save(
            this,
            FrameConfig(
                url = normalizedUrl,
                startMinutes = startMinutes,
                endMinutes = endMinutes,
            ),
        )
        FrameScheduleManager.sync(this)
        statusMessage = getString(R.string.settings_saved)
        return true
    }

    private fun openDisplay(url: String, startMinutes: Int, endMinutes: Int) {
        if (!saveConfig(url, startMinutes, endMinutes)) return
        permissionState = FramePreferences.permissionState(this)
        if (!permissionState.allRequiredGranted) {
            statusMessage = getString(R.string.required_permissions_missing)
            return
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun toggleDeviceAdmin() {
        if (permissionState.deviceAdminActive) {
            devicePolicyManager.removeActiveAdmin(adminComponent)
            permissionState = FramePreferences.permissionState(this)
            FrameScheduleManager.sync(this)
            return
        }
        startActivity(
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.device_admin_explanation),
                )
            },
        )
    }

    private fun requestOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun runScreenOffTest() {
        permissionState = FramePreferences.permissionState(this)
        if (!permissionState.deviceAdminActive || !permissionState.exactAlarmGranted) {
            statusMessage = getString(R.string.screen_test_permissions_missing)
            return
        }
        if (keyguardManager.isDeviceSecure) {
            statusMessage = getString(R.string.remove_screen_lock)
            return
        }

        val wakeIntent = WakeReceiver.pendingIntent(this)
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + WAKE_DELAY_MILLIS,
                wakeIntent,
            )
            statusMessage = getString(R.string.screen_off_scheduled)
            devicePolicyManager.lockNow()
        } catch (error: RuntimeException) {
            alarmManager.cancel(wakeIntent)
            statusMessage = getString(
                R.string.screen_off_failed,
                error.message.orEmpty(),
            )
        }
    }

    companion object {
        const val EXTRA_OPENED_FROM_DISPLAY = "opened_from_display"
        private const val WAKE_DELAY_MILLIS = 10_000L
    }
}

@Composable
private fun SettingsScreen(
    initialConfig: FrameConfig,
    permissionState: FramePermissionState,
    statusMessage: String?,
    onSave: (String, Int, Int) -> Boolean,
    onOpenDisplay: (String, Int, Int) -> Unit,
    onDeviceAdminClick: () -> Unit,
    onOverlayClick: () -> Unit,
    onExactAlarmClick: () -> Unit,
    onScreenOffTest: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(initialConfig.url) }
    var startMinutes by rememberSaveable { mutableIntStateOf(initialConfig.startMinutes) }
    var endMinutes by rememberSaveable { mutableIntStateOf(initialConfig.endMinutes) }
    var localMessage by rememberSaveable { mutableStateOf<String?>(null) }

    FrameTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 48.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.website_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(androidx.compose.ui.res.stringResource(R.string.website_url)) },
                    singleLine = true,
                )

                HorizontalDivider()
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.schedule_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                TimeSettingRow(
                    label = androidx.compose.ui.res.stringResource(R.string.start_time),
                    minutes = startMinutes,
                    onTimeChanged = { startMinutes = it },
                )
                TimeSettingRow(
                    label = androidx.compose.ui.res.stringResource(R.string.end_time),
                    minutes = endMinutes,
                    onTimeChanged = { endMinutes = it },
                )

                HorizontalDivider()
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.permissions_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                PermissionRow(
                    title = androidx.compose.ui.res.stringResource(R.string.screen_off_permission),
                    granted = permissionState.deviceAdminActive,
                    supported = permissionState.deviceAdminSupported,
                    onClick = onDeviceAdminClick,
                )
                PermissionRow(
                    title = androidx.compose.ui.res.stringResource(R.string.overlay_permission),
                    granted = permissionState.overlayGranted,
                    onClick = onOverlayClick,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PermissionRow(
                        title = androidx.compose.ui.res.stringResource(R.string.exact_alarm_permission),
                        granted = permissionState.exactAlarmGranted,
                        onClick = onExactAlarmClick,
                    )
                }
                Button(
                    onClick = onScreenOffTest,
                    enabled = permissionState.deviceAdminActive &&
                        permissionState.exactAlarmGranted,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(androidx.compose.ui.res.stringResource(R.string.turn_off_for_ten_seconds))
                }

                (localMessage ?: statusMessage)?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        localMessage = if (onSave(url, startMinutes, endMinutes)) {
                            null
                        } else {
                            localMessage
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(androidx.compose.ui.res.stringResource(R.string.save_settings))
                }
                Button(
                    onClick = { onOpenDisplay(url, startMinutes, endMinutes) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(androidx.compose.ui.res.stringResource(R.string.open_frame_display))
                }
            }
        }
    }
}

@Composable
private fun TimeSettingRow(
    label: String,
    minutes: Int,
    onTimeChanged: (Int) -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Button(
            onClick = {
                TimePickerDialog(
                    context,
                    { _, hour, minute -> onTimeChanged(hour * 60 + minute) },
                    minutes / 60,
                    minutes % 60,
                    true,
                ).show()
            },
        ) {
            Text(String.format(Locale.getDefault(), "%02d:%02d", minutes / 60, minutes % 60))
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    supported: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(
                text = when {
                    !supported -> androidx.compose.ui.res.stringResource(R.string.not_supported)
                    granted -> androidx.compose.ui.res.stringResource(R.string.permission_granted)
                    else -> androidx.compose.ui.res.stringResource(R.string.permission_not_granted)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(onClick = onClick, enabled = supported) {
            Text(
                if (granted) {
                    androidx.compose.ui.res.stringResource(R.string.disable_permission)
                } else {
                    androidx.compose.ui.res.stringResource(R.string.enable_permission)
                },
            )
        }
    }
}
