package dev.sogn.smartframe

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
import androidx.core.net.toUri

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
    private var screenStateReceiverRegistered = false
    private var activityStarted = false
    private var webContentPaused = false
    private var reloadWhenStarted = false
    private var networkLevel = NETWORK_UNAVAILABLE
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    reloadWhenStarted = true
                    webView?.post(::pauseWebContent)
                }

                Intent.ACTION_SCREEN_ON -> {
                    if (activityStarted && reloadWhenStarted) {
                        webView?.post {
                            resumeWebContent()
                            webView?.reload()
                            reloadWhenStarted = false
                        }
                    }
                }
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

        if (!SmartFramePreferences.isReady(this)) {
            openSettings()
            return
        }

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        showImmersiveMode()
        showWebView(SmartFramePreferences.load(this).url)
        registerScreenStateReceiver()
    }

    override fun onResume() {
        super.onResume()
        if (!SmartFramePreferences.isReady(this)) {
            if (!isFinishing) {
                openSettings()
            }
            return
        }

        SmartFrameScheduleManager.sync(this)
        showImmersiveMode()
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        resumeWebContent()
        if (reloadWhenStarted) {
            webView?.reload()
            reloadWhenStarted = false
        }
        if (webView == null) return
        if (networkCallbackRegistered) return

        networkLevel = currentNetworkLevel()
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        networkCallbackRegistered = true
    }

    override fun onStop() {
        activityStarted = false
        pauseWebContent()
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
        if (screenStateReceiverRegistered) {
            unregisterReceiver(screenStateReceiver)
            screenStateReceiverRegistered = false
        }
        if (webContentPaused) {
            webView?.resumeTimers()
            webContentPaused = false
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
                ): Boolean = !request.url.isHttps()

                @Suppress("OVERRIDE_DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    !url.toUri().isHttps()

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

    private fun pauseWebContent() {
        if (webContentPaused) return
        webView?.apply {
            onPause()
            pauseTimers()
            webContentPaused = true
            Log.i(TAG, "WebView rendering paused")
        }
    }

    private fun resumeWebContent() {
        if (!webContentPaused) return
        webView?.apply {
            onResume()
            resumeTimers()
            webContentPaused = false
            Log.i(TAG, "WebView rendering resumed")
        }
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

    private fun handleNetworkChange() {
        runOnUiThread {
            val newLevel = currentNetworkLevel()
            val becameAvailable =
                (networkLevel == NETWORK_UNAVAILABLE) && (newLevel > NETWORK_UNAVAILABLE)
            val becameValidated =
                (networkLevel < NETWORK_VALIDATED) && (newLevel == NETWORK_VALIDATED)
            val shouldReload = becameAvailable || becameValidated
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

private fun Uri.isHttps(): Boolean = scheme.equals("https", ignoreCase = true)
