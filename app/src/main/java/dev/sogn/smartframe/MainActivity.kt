package dev.sogn.smartframe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

class MainActivity : ComponentActivity() {
    private var geckoView: GeckoView? = null
    private var geckoSession: GeckoSession? = null
    private var settingsButton: Button? = null
    private val hideSettingsButton = Runnable {
        settingsButton?.visibility = View.GONE
    }
    private val connectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }
    private var networkCallbackRegistered = false
    private var screenOnReceiverRegistered = false
    private var networkLevel = NETWORK_UNAVAILABLE
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                Log.i(TAG, "Screen turned on; reloading GeckoView")
                geckoView?.post { geckoSession?.reload() }
            }
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkChange()
        }

        override fun onLost(network: Network) {
            handleNetworkChange()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            handleNetworkChange()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!FramePreferences.isReady(this)) {
            openSettings()
            return
        }

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        showImmersiveMode()
        showGeckoView(FramePreferences.load(this).url)
        registerScreenOnReceiver()
    }

    override fun onResume() {
        super.onResume()
        if (!FramePreferences.isReady(this)) {
            if (!isFinishing) {
                openSettings()
            }
            return
        }

        FrameScheduleManager.sync(this)
        showImmersiveMode()
    }

    override fun onStart() {
        super.onStart()
        if (geckoSession == null || networkCallbackRegistered) return

        networkLevel = currentNetworkLevel()
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        networkCallbackRegistered = true
    }

    override fun onStop() {
        if (networkCallbackRegistered) {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            showImmersiveMode()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && geckoSession != null) {
            showSettingsButton()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        settingsButton?.removeCallbacks(hideSettingsButton)
        settingsButton = null
        if (screenOnReceiverRegistered) {
            unregisterReceiver(screenOnReceiver)
            screenOnReceiverRegistered = false
        }
        geckoSession?.stop()
        geckoView?.releaseSession()
        geckoSession?.close()
        geckoSession = null
        geckoView = null
        super.onDestroy()
    }

    private fun openSettings() {
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_OPENED_FROM_DISPLAY, true),
        )
        finish()
    }

    private fun showGeckoView(url: String) {
        val container = FrameLayout(this)
        val browser = GeckoView(this).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        val session =
            GeckoSession(
                GeckoSessionSettings.Builder()
                    .allowJavascript(true)
                    .build(),
            ).apply {
                navigationDelegate =
                    object : GeckoSession.NavigationDelegate {
                        override fun onLoadRequest(
                            session: GeckoSession,
                            request: GeckoSession.NavigationDelegate.LoadRequest,
                        ): GeckoResult<AllowOrDeny> =
                            GeckoResult.fromValue(
                                if (request.uri.toUri().isHttps()) {
                                    AllowOrDeny.ALLOW
                                } else {
                                    AllowOrDeny.DENY
                                },
                            )
                    }
                permissionDelegate =
                    object : GeckoSession.PermissionDelegate {
                        override fun onContentPermissionRequest(
                            session: GeckoSession,
                            permission: GeckoSession.PermissionDelegate.ContentPermission,
                        ): GeckoResult<Int> =
                            GeckoResult.fromValue(
                                if (
                                    permission.permission ==
                                        GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                                    permission.permission ==
                                        GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                                ) {
                                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                                } else {
                                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT
                                },
                            )
                    }
                contentDelegate = object : GeckoSession.ContentDelegate {}
                open(getGeckoRuntime())
            }
        browser.setSession(session)
        session.loadUri(url)
        val button = Button(this).apply {
            text = getString(R.string.open_settings)
            alpha = SETTINGS_BUTTON_ALPHA
            visibility = View.GONE
            setOnClickListener { openSettings() }
        }
        container.addView(
            browser,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
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
        geckoView = browser
        geckoSession = session
        settingsButton = button
        setContentView(container)
    }

    private fun getGeckoRuntime(): GeckoRuntime =
        geckoRuntime
            ?: GeckoRuntime.create(
                applicationContext,
                GeckoRuntimeSettings.Builder()
                    .allowInsecureConnections(GeckoRuntimeSettings.HTTPS_ONLY)
                    .build(),
            ).also {
                geckoRuntime = it
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

    private fun registerScreenOnReceiver() {
        if (screenOnReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            screenOnReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenOnReceiverRegistered = true
    }

    private fun handleNetworkChange() {
        runOnUiThread {
            val newLevel = currentNetworkLevel()
            val shouldReload =
                (networkLevel == NETWORK_UNAVAILABLE && newLevel > NETWORK_UNAVAILABLE) ||
                    (networkLevel < NETWORK_VALIDATED && newLevel == NETWORK_VALIDATED)
            networkLevel = newLevel

            if (shouldReload) {
                Log.i(TAG, "Network became available; reloading GeckoView")
                geckoSession?.reload()
            }
        }
    }

    private fun currentNetworkLevel(): Int {
        val network = connectivityManager.activeNetwork ?: return NETWORK_UNAVAILABLE
        val capabilities =
            connectivityManager.getNetworkCapabilities(network) ?: return NETWORK_UNAVAILABLE
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NETWORK_UNAVAILABLE
        }
        return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            NETWORK_VALIDATED
        } else {
            NETWORK_AVAILABLE
        }
    }

    private companion object {
        const val TAG = "FrameMainActivity"
        const val NETWORK_UNAVAILABLE = 0
        const val NETWORK_AVAILABLE = 1
        const val NETWORK_VALIDATED = 2
        const val SETTINGS_BUTTON_VISIBLE_MILLIS = 5_000L
        const val SETTINGS_BUTTON_ALPHA = 0.65f
        const val SETTINGS_BUTTON_MARGIN_DP = 24

        var geckoRuntime: GeckoRuntime? = null
    }
}

private val Int.dp: Int
    get() = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

private fun Uri.isHttps(): Boolean = scheme.equals("https", ignoreCase = true)
