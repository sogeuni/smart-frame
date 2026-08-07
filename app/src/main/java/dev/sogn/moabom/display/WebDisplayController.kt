package dev.sogn.moabom.display

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import dev.sogn.moabom.SmartFramePreferences

/** WebView 표시, 재시도 및 생명주기를 담당합니다.
 * Owns WebView rendering, reload behaviour, and lifecycle management. */
class WebDisplayController(context: Context, url: String) : DisplayController {
    override val view: WebView = createWebView(context, url)
    override val supportsManualRefresh = true
    private var started = false
    private var paused = false
    private var reloadWhenStarted = false
    private var networkState = NetworkState.UNAVAILABLE

    /** WebView 타이머를 재개하고 보류된 페이지를 다시 불러옵니다.
     * Resumes WebView timers and reloads a deferred page. */
    override fun onStart() {
        started = true
        resume()
        if (reloadWhenStarted) {
            view.reload()
            reloadWhenStarted = false
        }
    }

    /** WebView 타이머를 멈춥니다. Pauses WebView timers. */
    override fun onStop() {
        started = false
        pause()
    }

    /** 화면이 꺼지면 다음 시작 시 새로고침하도록 보류합니다.
     * Defers a reload until the display becomes active again. */
    override fun onScreenOff() {
        reloadWhenStarted = true
        pause()
    }

    /** 화면이 켜졌고 Activity가 시작된 경우 콘텐츠를 복원합니다.
     * Restores content when the screen turns on while the Activity is started. */
    override fun onScreenOn() {
        if (!started) return
        resume()
        if (reloadWhenStarted) {
            view.reload()
            reloadWhenStarted = false
        }
    }

    /** 인터넷/검증 네트워크로 전환되면 WebView를 다시 불러옵니다.
     * Reloads WebView after connectivity becomes available or validated. */
    override fun onNetworkStateChanged(state: NetworkState) {
        val shouldReload =
            (networkState == NetworkState.UNAVAILABLE && state != NetworkState.UNAVAILABLE) ||
                (networkState == NetworkState.AVAILABLE && state == NetworkState.VALIDATED)
        networkState = state
        if (shouldReload) view.reload()
    }

    /** 초기 네트워크 상태는 reload 없이 저장합니다.
     * Stores initial network state without triggering a reload. */
    override fun initializeNetworkState(state: NetworkState) {
        networkState = state
    }

    /** 현재 URL을 다시 불러옵니다. Reloads the current URL. */
    override fun refresh() = view.reload()

    /** WebView 및 관련 렌더링 리소스를 영구 해제합니다.
     * Permanently releases WebView and its rendering resources. */
    override fun destroy() {
        if (paused) {
            view.resumeTimers()
            paused = false
        }
        view.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
    }

    /** WebView 실행을 일시 중단합니다. Suspends WebView execution. */
    private fun pause() {
        if (paused) return
        view.onPause()
        view.pauseTimers()
        paused = true
    }

    /** WebView 실행을 다시 시작합니다. Resumes WebView execution. */
    private fun resume() {
        if (!paused) return
        view.onResume()
        view.resumeTimers()
        paused = false
    }

    /** 보안 제약을 갖춘 WebView를 생성합니다. Creates a WebView with security constraints. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, url: String) = WebView(context).apply {
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
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !SmartFramePreferences.isAllowedWebUrl(request.url)

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                !SmartFramePreferences.isAllowedWebUrl(url.toUri())

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError,
            ) = handler.cancel()
        }
        loadUrl(url)
    }
}
