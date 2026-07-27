package com.sogn.smartframe

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import android.net.http.SslError

class MainActivity : ComponentActivity() {
    private var webView: WebView? = null
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
                Log.i(TAG, "Screen turned on; reloading WebView")
                webView?.post { webView?.reload() }
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
        showWebView(FramePreferences.load(this).url)
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
        if (!FrameScheduleManager.isDisplayTime(this)) {
            window.decorView.post {
                if (!isFinishing && !isDestroyed) {
                    FrameScheduleManager.turnScreenOff(this)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (webView == null || networkCallbackRegistered) return

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
        if (event.actionMasked == MotionEvent.ACTION_DOWN && webView != null) {
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
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun openSettings() {
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_OPENED_FROM_DISPLAY, true),
        )
        finish()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebView(url: String) {
        val container = FrameLayout(this)
        val browser = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = !request.url.isHttpOrHttps()

                @Suppress("OVERRIDE_DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    !Uri.parse(url).isHttpOrHttps()

                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: SslError,
                ) {
                    handler.cancel()
                }
            }
            loadUrl(url)
        }
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
        webView = browser
        settingsButton = button
        setContentView(container)
    }

    private fun showSettingsButton() {
        settingsButton?.apply {
            removeCallbacks(hideSettingsButton)
            visibility = View.VISIBLE
            postDelayed(hideSettingsButton, SETTINGS_BUTTON_VISIBLE_MILLIS)
        }
    }

    private fun showImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        WindowInsetsControllerCompat(window, window.decorView).apply {
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
                Log.i(TAG, "Network became available; reloading WebView")
                webView?.reload()
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
    }
}

private val Int.dp: Int
    get() = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

private fun Uri.isHttpOrHttps(): Boolean =
    scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
