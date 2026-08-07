package dev.sogn.moabom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.sogn.moabom.display.DisplayController
import dev.sogn.moabom.display.ImageDisplayController
import dev.sogn.moabom.display.NetworkState
import dev.sogn.moabom.display.WebDisplayController
import dev.sogn.moabom.image.ImageProviderRegistry

/** 화면 표시 controller를 호스팅하고 공통 시스템 UI만 관리합니다.
 * Hosts a display controller and manages only common system UI concerns. */
class MainActivity : ComponentActivity() {
    private var displayController: DisplayController? = null
    private var settingsButton: Button? = null
    private var refreshButton: Button? = null
    private var networkCallbackRegistered = false
    private var screenStateReceiverRegistered = false
    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }
    private val hideControlButtons = Runnable {
        settingsButton?.visibility = View.GONE
        refreshButton?.visibility = View.GONE
    }
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> displayController?.onScreenOff()
                Intent.ACTION_SCREEN_ON -> displayController?.onScreenOn()
            }
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = dispatchNetworkState()
        override fun onLost(network: Network) = dispatchNetworkState()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            dispatchNetworkState()
    }

    /** 설정에 맞는 화면 controller와 공통 조작 버튼을 만듭니다.
     * Creates the configured display controller and common control buttons. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SmartFramePreferences.isReady(this)) {
            openSettings()
            return
        }
        val config = SmartFramePreferences.load(this)
        val controller = when (config.displayMode) {
            DisplayMode.WEBVIEW -> WebDisplayController(this, config.url)
            DisplayMode.IMAGE -> ImageDisplayController(
                context = this,
                imageProvider = requireNotNull(ImageProviderRegistry.providerFor(config.displayMode)),
                configProvider = { SmartFramePreferences.load(this) },
            )
        }
        displayController = controller
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        showImmersiveMode()
        showDisplay(controller)
        registerScreenStateReceiver()
    }

    /** 스케줄을 동기화하고 전체 화면 상태를 복구합니다.
     * Synchronizes scheduling and restores immersive mode. */
    override fun onResume() {
        super.onResume()
        if (!SmartFramePreferences.isReady(this)) {
            if (!isFinishing) openSettings()
            return
        }
        SmartFrameScheduleManager.sync(this)
        showImmersiveMode()
    }

    /** controller와 연결 상태 감시를 시작합니다.
     * Starts the controller and connectivity monitoring. */
    override fun onStart() {
        super.onStart()
        displayController?.onStart()
        if (displayController != null && !networkCallbackRegistered) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
            displayController?.initializeNetworkState(currentNetworkState())
        }
    }

    /** controller와 연결 상태 감시를 중지합니다.
     * Stops the controller and connectivity monitoring. */
    override fun onStop() {
        displayController?.onStop()
        if (networkCallbackRegistered) {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        }
        super.onStop()
    }

    /** 포커스 복귀 시 시스템 바를 숨깁니다. Hides system bars when focus returns. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) showImmersiveMode()
    }

    /** 화면 터치 시 공통 조작 버튼을 잠시 표시합니다.
     * Shows common controls temporarily after a display touch. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && displayController != null) showControls()
        return super.dispatchTouchEvent(event)
    }

    /** controller와 broadcast receiver를 정리합니다.
     * Releases the controller and broadcast receiver. */
    override fun onDestroy() {
        settingsButton?.removeCallbacks(hideControlButtons)
        refreshButton?.removeCallbacks(hideControlButtons)
        displayController?.destroy()
        displayController = null
        if (screenStateReceiverRegistered) {
            unregisterReceiver(screenStateReceiver)
            screenStateReceiverRegistered = false
        }
        super.onDestroy()
    }

    /** controller 화면 위에 공통 설정·새로고침 버튼을 배치합니다.
     * Places common settings and refresh controls over the controller view. */
    private fun showDisplay(controller: DisplayController) {
        val root = FrameLayout(this)
        root.addView(controller.view, matchParentLayoutParams())
        val settings = createSettingsButton()
        if (controller.supportsManualRefresh) {
            val refresh = createRefreshButton(controller)
            addWebControls(root, refresh, settings)
            refreshButton = refresh
        } else {
            addSettingsButton(root, settings)
        }
        settingsButton = settings
        setContentView(root)
    }

    /** 현재 네트워크 상태를 controller에 전달합니다.
     * Delivers the current network state to the controller. */
    private fun dispatchNetworkState() {
        runOnUiThread { displayController?.onNetworkStateChanged(currentNetworkState()) }
    }

    /** 활성 네트워크의 인터넷/검증 상태를 계산합니다.
     * Calculates the active network's internet and validation state. */
    private fun currentNetworkState(): NetworkState {
        val network = connectivityManager.activeNetwork ?: return NetworkState.UNAVAILABLE
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkState.UNAVAILABLE
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkState.UNAVAILABLE
        return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            NetworkState.VALIDATED
        } else {
            NetworkState.AVAILABLE
        }
    }

    /** 설정 화면을 열고 현재 표시 Activity를 종료합니다.
     * Opens settings and finishes the current display Activity. */
    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_OPENED_FROM_DISPLAY, true))
        finish()
    }

    /** 설정 버튼을 만듭니다. Creates the settings button. */
    private fun createSettingsButton() = Button(this).apply {
        text = getString(R.string.open_settings)
        alpha = CONTROL_BUTTON_ALPHA
        visibility = View.GONE
        setOnClickListener { openSettings() }
    }

    /** Web controller용 새로고침 버튼을 만듭니다. Creates a Web controller refresh button. */
    private fun createRefreshButton(controller: DisplayController) = Button(this).apply {
        text = getString(R.string.refresh)
        alpha = CONTROL_BUTTON_ALPHA
        visibility = View.GONE
        setOnClickListener { controller.refresh() }
    }

    /** Web 화면 조작 버튼을 수평으로 배치합니다. Places Web controls horizontally. */
    private fun addWebControls(container: FrameLayout, refresh: Button, settings: Button) {
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(refresh)
            addView(settings)
        }
        addTopEnd(container, controls)
    }

    /** 이미지 화면 설정 버튼을 배치합니다. Places the image settings button. */
    private fun addSettingsButton(container: FrameLayout, button: Button) = addTopEnd(container, button)

    /** 우상단 여백을 적용한 View를 배치합니다. Places a View with a top-end margin. */
    private fun addTopEnd(container: FrameLayout, view: View) {
        container.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply {
            topMargin = CONTROL_MARGIN_DP.dp
            marginEnd = CONTROL_MARGIN_DP.dp
        })
    }

    /** 조작 버튼을 보이고 자동 숨김을 예약합니다. Shows and schedules hiding controls. */
    private fun showControls() {
        settingsButton?.removeCallbacks(hideControlButtons)
        refreshButton?.removeCallbacks(hideControlButtons)
        settingsButton?.visibility = View.VISIBLE
        refreshButton?.visibility = View.VISIBLE
        settingsButton?.postDelayed(hideControlButtons, CONTROL_VISIBLE_MILLIS)
    }

    /** immersive UI를 적용합니다. Applies immersive system UI. */
    private fun showImmersiveMode() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** 화면 on/off receiver를 등록합니다. Registers the screen on/off receiver. */
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

    /** 전체 화면 크기 LayoutParams를 만듭니다. Creates match-parent LayoutParams. */
    private fun matchParentLayoutParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )

    private companion object {
        const val CONTROL_VISIBLE_MILLIS = 5_000L
        const val CONTROL_BUTTON_ALPHA = 0.65f
        const val CONTROL_MARGIN_DP = 24
    }
}

/** dp를 픽셀로 변환합니다. Converts dp to physical pixels. */
private val Int.dp: Int
    get() = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
