package dev.sogn.moabom

import android.app.AlarmManager
import android.app.AlertDialog
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
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import dev.sogn.moabom.image.onedrive.OneDriveAuthManager
import dev.sogn.moabom.image.onedrive.OneDriveFolder
import dev.sogn.moabom.image.onedrive.OneDriveGraphClient
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
        if (SmartFramePreferences.load(this).displayMode == DisplayMode.WEBVIEW) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        OneDriveAuthManager.loadAccount(this) { result ->
            result.fold(
                onSuccess = { account ->
                    if (account == null) {
                        showMessage(R.string.onedrive_login_required)
                    } else {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                },
                onFailure = ::showError,
            )
        }
    }

    internal fun toggleDeviceAdmin() {
        val permissionState = SmartFramePreferences.permissionState(this)
        if (permissionState.deviceAdminActive) {
            devicePolicyManager.removeActiveAdmin(adminComponent)
            updateDeviceAdminSummary()
            window.decorView.post { SmartFrameScheduleManager.sync(this) }
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

    internal fun showError(error: Throwable) {
        Toast.makeText(
            this,
            getString(R.string.onedrive_error, error.message.orEmpty()),
            Toast.LENGTH_LONG,
        ).show()
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
    private lateinit var displayModePreference: ListPreference
    private lateinit var websiteCategory: PreferenceCategory
    private lateinit var oneDriveCategory: PreferenceCategory
    private lateinit var urlPreference: EditTextPreference
    private lateinit var accountPreference: Preference
    private lateinit var folderPreference: Preference
    private lateinit var photoIntervalPreference: EditTextPreference
    private lateinit var pollIntervalPreference: EditTextPreference
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

        displayModePreference = requirePreference(SmartFramePreferences.KEY_DISPLAY_MODE)
        websiteCategory = requirePreference(KEY_WEBSITE_CATEGORY)
        oneDriveCategory = requirePreference(KEY_ONEDRIVE_CATEGORY)
        urlPreference = requirePreference(SmartFramePreferences.KEY_URL)
        accountPreference = requirePreference(SmartFramePreferences.KEY_ONEDRIVE_ACCOUNT)
        folderPreference = requirePreference(SmartFramePreferences.KEY_ONEDRIVE_FOLDER)
        photoIntervalPreference =
            requirePreference(SmartFramePreferences.KEY_PHOTO_INTERVAL_SECONDS)
        pollIntervalPreference =
            requirePreference(SmartFramePreferences.KEY_POLL_INTERVAL_MINUTES)
        schedulePreference = requirePreference(SmartFramePreferences.KEY_SCHEDULE_ENABLED)
        startTimePreference = requirePreference(SmartFramePreferences.KEY_START_MINUTES)
        endTimePreference = requirePreference(SmartFramePreferences.KEY_END_MINUTES)
        deviceAdminPreference = requirePreference(KEY_DEVICE_ADMIN)
        overlayPreference = requirePreference(KEY_OVERLAY)
        exactAlarmPreference = requirePreference(KEY_EXACT_ALARM)

        bindDisplayModePreference()
        bindUrlPreference()
        bindOneDrivePreferences()
        bindIntervalPreferences()
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
        if (!this::displayModePreference.isInitialized) return
        val config = SmartFramePreferences.load(requireContext())
        val oneDriveSelected = config.displayMode == DisplayMode.IMAGE
        displayModePreference.value = config.displayMode.preferenceValue
        displayModePreference.summary = displayModePreference.entry
        websiteCategory.isVisible = !oneDriveSelected
        oneDriveCategory.isVisible = oneDriveSelected
        urlPreference.text = config.url
        folderPreference.summary = config.oneDriveFolderName.ifBlank {
            getString(R.string.onedrive_folder_not_selected)
        }
        photoIntervalPreference.text = config.photoIntervalSeconds.toString()
        photoIntervalPreference.summary =
            resources.getQuantityString(
                R.plurals.seconds_value,
                config.photoIntervalSeconds,
                config.photoIntervalSeconds,
            )
        pollIntervalPreference.text = config.pollIntervalMinutes.toString()
        pollIntervalPreference.summary =
            resources.getQuantityString(
                R.plurals.minutes_value,
                config.pollIntervalMinutes,
                config.pollIntervalMinutes,
            )
        schedulePreference.isChecked = config.scheduleEnabled
        startTimePreference.summary = formatTime(config.startMinutes)
        endTimePreference.summary = formatTime(config.endMinutes)

        if (oneDriveSelected) {
            OneDriveAuthManager.loadAccount(requireContext()) { result ->
                if (!isAdded) return@loadAccount
                result.fold(
                    onSuccess = { account ->
                        val signedIn = account != null
                        accountPreference.summary = account?.username
                            ?: getString(R.string.onedrive_not_signed_in)
                        folderPreference.isEnabled = signedIn
                    },
                    onFailure = { error ->
                        accountPreference.summary = error.message
                        folderPreference.isEnabled = false
                    },
                )
            }
        }

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

    private fun bindDisplayModePreference() {
        displayModePreference.isPersistent = false
        displayModePreference.setOnPreferenceChangeListener { _, newValue ->
            val mode = DisplayMode.fromPreference(newValue as String)
            val config = SmartFramePreferences.load(requireContext())
            SmartFramePreferences.save(requireContext(), config.copy(displayMode = mode))
            refreshState()
            true
        }
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

    private fun bindOneDrivePreferences() {
        accountPreference.setOnPreferenceClickListener {
            OneDriveAuthManager.loadAccount(requireContext()) { result ->
                result.fold(
                    onSuccess = { account ->
                        if (account == null) {
                            OneDriveAuthManager.signIn(host) { signInResult ->
                                signInResult.fold(
                                    onSuccess = { refreshState() },
                                    onFailure = host::showError,
                                )
                            }
                        } else {
                            confirmSignOut()
                        }
                    },
                    onFailure = host::showError,
                )
            }
            true
        }
        folderPreference.setOnPreferenceClickListener {
            OneDriveFolderPicker.show(host) { folder ->
                val config = SmartFramePreferences.load(requireContext())
                SmartFramePreferences.save(
                    requireContext(),
                    config.copy(
                        oneDriveFolderId = folder.id,
                        oneDriveFolderName = folder.name,
                    ),
                )
                refreshState()
            }
            true
        }
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(host)
            .setTitle(R.string.onedrive_sign_out)
            .setMessage(R.string.onedrive_sign_out_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.onedrive_sign_out) { _, _ ->
                OneDriveAuthManager.signOut(requireContext()) { result ->
                    result.fold(
                        onSuccess = {
                            SmartFramePreferences.clearOneDriveFolder(requireContext())
                            refreshState()
                        },
                        onFailure = host::showError,
                    )
                }
            }
            .show()
    }

    private fun bindIntervalPreferences() {
        bindNumberPreference(
            preference = photoIntervalPreference,
            min = 5,
            max = 3_600,
            invalidMessage = R.string.invalid_photo_interval,
        ) { value, config ->
            config.copy(photoIntervalSeconds = value)
        }
        bindNumberPreference(
            preference = pollIntervalPreference,
            min = 1,
            max = 1_440,
            invalidMessage = R.string.invalid_poll_interval,
        ) { value, config ->
            config.copy(pollIntervalMinutes = value)
        }
    }

    private fun bindNumberPreference(
        preference: EditTextPreference,
        min: Int,
        max: Int,
        invalidMessage: Int,
        update: (Int, SmartFrameConfig) -> SmartFrameConfig,
    ) {
        preference.isPersistent = false
        preference.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
            editText.setSelectAllOnFocus(true)
        }
        preference.setOnPreferenceChangeListener { _, newValue ->
            val value = (newValue as String).toIntOrNull()
            if (value == null || value !in min..max) {
                host.showMessage(invalidMessage)
                return@setOnPreferenceChangeListener false
            }
            val config = SmartFramePreferences.load(requireContext())
            SmartFramePreferences.save(requireContext(), update(value, config))
            refreshState()
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
        requireNotNull(findPreference<T>(key)) { "Missing preference: $key" }

    private companion object {
        const val KEY_WEBSITE_CATEGORY = "website_category"
        const val KEY_ONEDRIVE_CATEGORY = "onedrive_category"
        const val KEY_DEVICE_ADMIN = "device_admin"
        const val KEY_OVERLAY = "overlay"
        const val KEY_EXACT_ALARM = "exact_alarm"
        const val KEY_SCREEN_OFF_TEST = "screen_off_test"
        const val KEY_OPEN_DISPLAY = "open_display"
    }
}

private object OneDriveFolderPicker {
    private data class Location(val folder: OneDriveFolder)

    fun show(activity: SettingsActivity, onSelected: (OneDriveFolder) -> Unit) {
        val stack = mutableListOf<Location>()
        showLevel(activity, stack, onSelected)
    }

    private fun showLevel(
        activity: SettingsActivity,
        stack: MutableList<Location>,
        onSelected: (OneDriveFolder) -> Unit,
    ) {
        val parent = stack.lastOrNull()?.folder
        OneDriveGraphClient.listFolders(activity, parent?.id) { result ->
            result.fold(
                onSuccess = { folders ->
                    val hasBack = stack.isNotEmpty()
                    val labels = buildList {
                        if (hasBack) add(activity.getString(R.string.parent_folder))
                        addAll(folders.map { "📁 ${it.name}" })
                    }
                    val builder = AlertDialog.Builder(activity)
                        .setTitle(parent?.name ?: activity.getString(R.string.onedrive_root))
                        .setItems(labels.toTypedArray()) { _, index ->
                            if (hasBack && index == 0) {
                                stack.removeAt(stack.lastIndex)
                            } else {
                                val folderIndex = index - if (hasBack) 1 else 0
                                stack += Location(folders[folderIndex])
                            }
                            showLevel(activity, stack, onSelected)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                    if (parent != null) {
                        builder.setPositiveButton(R.string.select_this_folder) { _, _ ->
                            onSelected(parent)
                        }
                    }
                    builder.show()
                },
                onFailure = activity::showError,
            )
        }
    }
}
