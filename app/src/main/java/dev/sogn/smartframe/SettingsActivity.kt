package dev.sogn.smartframe

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Toast
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import java.util.Locale

class SettingsActivity : FragmentActivity() {
    private val devicePolicyManager by lazy {
        getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    private val alarmManager by lazy {
        getSystemService(ALARM_SERVICE) as AlarmManager
    }
    private val keyguardManager by lazy {
        getSystemService(KEYGUARD_SERVICE) as KeyguardManager
    }
    private val adminComponent by lazy {
        ComponentName(this, ScreenOffAdminReceiver::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            SmartFrameScheduleManager.applyCurrentState(applicationContext)
        }
    }

    internal fun openDisplay() {
        if (!SmartFramePreferences.isReady(this)) {
            showMessage(R.string.required_permissions_missing)
            return
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    internal fun toggleDeviceAdmin() {
        val permissionState = SmartFramePreferences.permissionState(this)
        if (permissionState.deviceAdminActive) {
            devicePolicyManager.removeActiveAdmin(adminComponent)
            updateDeviceAdminSummary()
            window.decorView.post {
                SmartFrameScheduleManager.sync(this)
            }
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

    internal fun requestOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri(),
            ),
        )
    }

    internal fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    "package:$packageName".toUri(),
                ),
            )
        }
    }

    internal fun runScreenOffTest() {
        val permissionState = SmartFramePreferences.permissionState(this)
        val exactAlarmUnavailable =
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) &&
                (!alarmManager.canScheduleExactAlarms())
        if (!permissionState.deviceAdminActive || exactAlarmUnavailable) {
            showMessage(R.string.screen_test_permissions_missing)
            return
        }
        if (keyguardManager.isDeviceSecure) {
            showMessage(R.string.remove_screen_lock)
            return
        }

        val wakeIntent = WakeReceiver.pendingIntent(this)
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + WAKE_DELAY_MILLIS,
                wakeIntent,
            )
            showMessage(R.string.screen_off_scheduled)
            devicePolicyManager.lockNow()
        } catch (error: RuntimeException) {
            alarmManager.cancel(wakeIntent)
            Toast.makeText(
                this,
                getString(R.string.screen_off_failed, error.message.orEmpty()),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    internal fun showMessage(messageResId: Int) {
        Toast.makeText(this, messageResId, Toast.LENGTH_SHORT).show()
    }

    private fun updateDeviceAdminSummary() {
        (supportFragmentManager.findFragmentById(R.id.settings_container) as? SettingsFragment)
            ?.updateDeviceAdminSummary()
    }

    companion object {
        const val EXTRA_OPENED_FROM_DISPLAY = "opened_from_display"
        private const val WAKE_DELAY_MILLIS = 10_000L
    }
}

class SettingsFragment : PreferenceFragmentCompat() {
    private lateinit var urlPreference: EditTextPreference
    private lateinit var schedulePreference: SwitchPreferenceCompat
    private lateinit var startTimePreference: Preference
    private lateinit var endTimePreference: Preference
    private lateinit var deviceAdminPreference: Preference
    private lateinit var overlayPreference: Preference
    private lateinit var exactAlarmPreference: Preference

    private val host: SettingsActivity
        get() = requireActivity() as SettingsActivity

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        urlPreference = requirePreference(SmartFramePreferences.KEY_URL)
        schedulePreference = requirePreference(SmartFramePreferences.KEY_SCHEDULE_ENABLED)
        startTimePreference = requirePreference(SmartFramePreferences.KEY_START_MINUTES)
        endTimePreference = requirePreference(SmartFramePreferences.KEY_END_MINUTES)
        deviceAdminPreference = requirePreference(KEY_DEVICE_ADMIN)
        overlayPreference = requirePreference(KEY_OVERLAY)
        exactAlarmPreference = requirePreference(KEY_EXACT_ALARM)

        bindUrlPreference()
        bindSchedulePreference()
        bindTimePreferences()
        bindPermissionPreferences()
        requirePreference<Preference>(KEY_SCREEN_OFF_TEST).setOnPreferenceClickListener {
            host.runScreenOffTest()
            true
        }
        requirePreference<Preference>(KEY_OPEN_DISPLAY).setOnPreferenceClickListener {
            host.openDisplay()
            true
        }
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        SmartFrameScheduleManager.sync(requireContext())
    }

    internal fun refreshState() {
        if (!this::urlPreference.isInitialized) return

        val config = SmartFramePreferences.load(requireContext())
        urlPreference.text = config.url
        schedulePreference.isChecked = config.scheduleEnabled
        startTimePreference.summary = formatTime(config.startMinutes)
        endTimePreference.summary = formatTime(config.endMinutes)

        val permissionState = SmartFramePreferences.permissionState(requireContext())
        deviceAdminPreference.isEnabled = permissionState.deviceAdminSupported
        deviceAdminPreference.summary = permissionSummary(
            granted = permissionState.deviceAdminActive,
            supported = permissionState.deviceAdminSupported,
        )
        overlayPreference.summary = permissionSummary(permissionState.overlayGranted)
        exactAlarmPreference.isVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        exactAlarmPreference.summary = permissionSummary(permissionState.exactAlarmGranted)
    }

    internal fun updateDeviceAdminSummary() {
        if (!this::deviceAdminPreference.isInitialized) return
        deviceAdminPreference.summary = permissionSummary(granted = false)
    }

    private fun bindUrlPreference() {
        urlPreference.isPersistent = false
        urlPreference.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        urlPreference.setOnBindEditTextListener { editText ->
            editText.inputType =
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            editText.setSelectAllOnFocus(false)
        }
        urlPreference.setOnPreferenceChangeListener { _, newValue ->
            val normalizedUrl = SmartFramePreferences.normalizeUrl(newValue as String)
            if (normalizedUrl == null) {
                host.showMessage(R.string.invalid_url)
                return@setOnPreferenceChangeListener false
            }

            val config = SmartFramePreferences.load(requireContext())
            SmartFramePreferences.save(requireContext(), config.copy(url = normalizedUrl))
            urlPreference.text = normalizedUrl
            false
        }
    }

    private fun bindSchedulePreference() {
        schedulePreference.isPersistent = false
        schedulePreference.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            val config = SmartFramePreferences.load(requireContext())
            SmartFramePreferences.save(requireContext(), config.copy(scheduleEnabled = enabled))
            schedulePreference.isChecked = enabled
            SmartFrameScheduleManager.sync(requireContext())
            true
        }
    }

    private fun bindTimePreferences() {
        startTimePreference.isPersistent = false
        endTimePreference.isPersistent = false
        startTimePreference.setOnPreferenceClickListener {
            showTimePicker(isStartTime = true)
            true
        }
        endTimePreference.setOnPreferenceClickListener {
            showTimePicker(isStartTime = false)
            true
        }
    }

    private fun bindPermissionPreferences() {
        deviceAdminPreference.setOnPreferenceClickListener {
            host.toggleDeviceAdmin()
            true
        }
        overlayPreference.setOnPreferenceClickListener {
            host.requestOverlayPermission()
            true
        }
        exactAlarmPreference.setOnPreferenceClickListener {
            host.requestExactAlarmPermission()
            true
        }
    }

    private fun showTimePicker(isStartTime: Boolean) {
        val config = SmartFramePreferences.load(requireContext())
        val currentMinutes = if (isStartTime) config.startMinutes else config.endMinutes
        TimePickerDialog(
            requireContext(),
            { _, hour, minute ->
                val selectedMinutes = (hour * 60) + minute
                val otherMinutes = if (isStartTime) config.endMinutes else config.startMinutes
                if (selectedMinutes == otherMinutes) {
                    host.showMessage(R.string.invalid_schedule)
                    return@TimePickerDialog
                }

                val updatedConfig = if (isStartTime) {
                    config.copy(startMinutes = selectedMinutes)
                } else {
                    config.copy(endMinutes = selectedMinutes)
                }
                SmartFramePreferences.save(requireContext(), updatedConfig)
                refreshState()
                SmartFrameScheduleManager.sync(requireContext())
            },
            currentMinutes / 60,
            currentMinutes % 60,
            true,
        ).show()
    }

    private fun permissionSummary(granted: Boolean, supported: Boolean = true): String =
        getString(
            when {
                !supported -> R.string.not_supported
                granted -> R.string.permission_granted
                else -> R.string.permission_not_granted
            },
        )

    private fun formatTime(minutes: Int): String =
        String.format(Locale.getDefault(), "%02d:%02d", minutes / 60, minutes % 60)

    private inline fun <reified T : Preference> requirePreference(key: String): T =
        requireNotNull(findPreference<T>(key)) {
            "Missing preference: $key"
        }

    private companion object {
        const val KEY_DEVICE_ADMIN = "device_admin"
        const val KEY_OVERLAY = "overlay"
        const val KEY_EXACT_ALARM = "exact_alarm"
        const val KEY_SCREEN_OFF_TEST = "screen_off_test"
        const val KEY_OPEN_DISPLAY = "open_display"
    }
}
