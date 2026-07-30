package dev.sogn.smartframe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private var imageView: ImageView? = null
    private var statusText: TextView? = null
    private var settingsButton: Button? = null
    private val handler = Handler(Looper.getMainLooper())
    private var photos: List<OneDrivePhoto> = emptyList()
    private val photoQueue = ArrayDeque<OneDrivePhoto>()
    private var currentPhotoId: String? = null
    private var refreshInProgress = false
    private var activityStarted = false
    private var photoTasksActive = false
    private var networkCallbackRegistered = false
    private var screenStateReceiverRegistered = false
    private val hideSettingsButton = Runnable {
        settingsButton?.visibility = View.GONE
    }
    private val showNextPhoto = Runnable { displayNextPhoto() }
    private val pollPhotos = Runnable {
        refreshPhotos()
        schedulePhotoPoll()
    }
    private val connectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> stopPhotoTasks()
                Intent.ACTION_SCREEN_ON -> if (activityStarted) startPhotoTasks()
            }
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = handleNetworkChange()
        override fun onLost(network: Network) = handleNetworkChange()
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = handleNetworkChange()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SmartFramePreferences.isReady(this)) {
            openSettings()
            return
        }

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        showImmersiveMode()
        showPhotoDisplay()
        registerScreenStateReceiver()
    }

    override fun onResume() {
        super.onResume()
        if (!SmartFramePreferences.isReady(this)) {
            if (!isFinishing) openSettings()
            return
        }
        SmartFrameScheduleManager.sync(this)
        showImmersiveMode()
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        if (imageView == null) return
        if (!networkCallbackRegistered) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }
        startPhotoTasks()
    }

    override fun onStop() {
        activityStarted = false
        stopPhotoTasks()
        if (networkCallbackRegistered) {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) showImmersiveMode()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && imageView != null) {
            showSettingsButton()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        stopPhotoTasks()
        settingsButton?.removeCallbacks(hideSettingsButton)
        settingsButton = null
        imageView = null
        statusText = null
        if (screenStateReceiverRegistered) {
            unregisterReceiver(screenStateReceiver)
            screenStateReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun showPhotoDisplay() {
        val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val photo = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.CENTER_CROP
            keepScreenOn = true
        }
        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = STATUS_TEXT_SIZE_SP
            gravity = Gravity.CENTER
            text = getString(R.string.onedrive_loading_photos)
            setPadding(STATUS_PADDING_DP.dp, 0, STATUS_PADDING_DP.dp, 0)
        }
        val button = Button(this).apply {
            text = getString(R.string.open_settings)
            alpha = SETTINGS_BUTTON_ALPHA
            visibility = View.GONE
            setOnClickListener { openSettings() }
        }
        container.addView(photo, matchParentLayoutParams())
        container.addView(status, matchParentLayoutParams())
        container.addView(
            button,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = SETTINGS_BUTTON_MARGIN_DP.dp
                marginEnd = SETTINGS_BUTTON_MARGIN_DP.dp
            },
        )
        imageView = photo
        statusText = status
        settingsButton = button
        setContentView(container)
    }

    private fun startPhotoTasks() {
        if (!activityStarted) return
        photoTasksActive = true
        handler.removeCallbacks(showNextPhoto)
        handler.removeCallbacks(pollPhotos)
        refreshPhotos()
        if (currentPhotoId == null && photos.isNotEmpty()) {
            displayNextPhoto()
        }
        schedulePhotoPoll()
    }

    private fun stopPhotoTasks() {
        photoTasksActive = false
        handler.removeCallbacks(showNextPhoto)
        handler.removeCallbacks(pollPhotos)
    }

    private fun refreshPhotos() {
        if (refreshInProgress) return
        val folderId = SmartFramePreferences.load(this).oneDriveFolderId
        if (folderId.isBlank()) return
        refreshInProgress = true
        OneDriveGraphClient.listPhotos(this, folderId) { result ->
            refreshInProgress = false
            if (!activityStarted) return@listPhotos
            result.fold(
                onSuccess = { updatedPhotos ->
                    val oldIds = photos.mapTo(mutableSetOf(), OneDrivePhoto::id)
                    val newIds = updatedPhotos.mapTo(mutableSetOf(), OneDrivePhoto::id)
                    photos = updatedPhotos
                    if (oldIds != newIds) photoQueue.clear()
                    if (updatedPhotos.isEmpty()) {
                        statusText?.apply {
                            text = getString(R.string.onedrive_folder_has_no_photos)
                            visibility = View.VISIBLE
                        }
                    } else {
                        statusText?.visibility = View.GONE
                        if (photoTasksActive && currentPhotoId == null) displayNextPhoto()
                    }
                },
                onFailure = { error ->
                    Log.w(TAG, "Could not refresh OneDrive photos", error)
                    statusText?.apply {
                        text = getString(
                            R.string.onedrive_photo_refresh_failed,
                            error.message.orEmpty(),
                        )
                        visibility = View.VISIBLE
                    }
                },
            )
        }
    }

    private fun displayNextPhoto() {
        handler.removeCallbacks(showNextPhoto)
        if (!photoTasksActive || photos.isEmpty()) return
        if (photoQueue.isEmpty()) {
            val shuffled = photos.shuffled().toMutableList()
            if (shuffled.size > 1 && shuffled.first().id == currentPhotoId) {
                val first = shuffled.removeAt(0)
                shuffled += first
            }
            photoQueue.addAll(shuffled)
        }
        val next = photoQueue.removeFirst()
        val view = imageView ?: return
        OneDrivePhotoLoader.load(
            context = this,
            photo = next,
            targetWidth = view.width,
            targetHeight = view.height,
        ) { result ->
            if (!photoTasksActive) return@load
            result.fold(
                onSuccess = { bitmap ->
                    view.alpha = 0f
                    view.setImageBitmap(bitmap)
                    view.animate().alpha(1f).setDuration(FADE_DURATION_MILLIS).start()
                    currentPhotoId = next.id
                    statusText?.visibility = View.GONE
                },
                onFailure = { error ->
                    Log.w(TAG, "Could not load OneDrive photo ${next.name}", error)
                    statusText?.apply {
                        text = getString(R.string.onedrive_photo_load_failed)
                        visibility = View.VISIBLE
                    }
                },
            )
            scheduleNextPhoto()
        }
    }

    private fun scheduleNextPhoto() {
        if (!photoTasksActive) return
        val delay = SmartFramePreferences.load(this).photoIntervalSeconds * 1_000L
        handler.postDelayed(showNextPhoto, delay)
    }

    private fun schedulePhotoPoll() {
        if (!photoTasksActive) return
        val delay = SmartFramePreferences.load(this).pollIntervalMinutes * 60_000L
        handler.removeCallbacks(pollPhotos)
        handler.postDelayed(pollPhotos, delay)
    }

    private fun handleNetworkChange() {
        runOnUiThread {
            if (photoTasksActive && currentNetworkAvailable()) {
                refreshPhotos()
            }
        }
    }

    private fun currentNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun openSettings() {
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_OPENED_FROM_DISPLAY, true),
        )
        finish()
    }

    private fun showSettingsButton() {
        settingsButton?.apply {
            removeCallbacks(hideSettingsButton)
            visibility = View.VISIBLE
            postDelayed(hideSettingsButton, SETTINGS_BUTTON_VISIBLE_MILLIS)
        }
    }

    private fun showImmersiveMode() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun registerScreenStateReceiver() {
        if (screenStateReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenStateReceiverRegistered = true
    }

    private fun matchParentLayoutParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )

    private companion object {
        const val TAG = "FrameMainActivity"
        const val SETTINGS_BUTTON_VISIBLE_MILLIS = 5_000L
        const val FADE_DURATION_MILLIS = 600L
        const val SETTINGS_BUTTON_ALPHA = 0.65f
        const val STATUS_TEXT_SIZE_SP = 18f
        const val SETTINGS_BUTTON_MARGIN_DP = 24
        const val STATUS_PADDING_DP = 32
    }
}

private val Int.dp: Int
    get() = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
