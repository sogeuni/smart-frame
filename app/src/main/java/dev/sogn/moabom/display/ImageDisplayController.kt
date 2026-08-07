package dev.sogn.moabom.display

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import dev.sogn.moabom.R
import dev.sogn.moabom.SmartFrameConfig
import dev.sogn.moabom.image.DisplayImage
import dev.sogn.moabom.image.ImageProvider

/** 공통 ImageProvider로 이미지를 순환 표시합니다.
 * Displays a rotating image slideshow through a common ImageProvider. */
class ImageDisplayController(
    private val context: Context,
    private val imageProvider: ImageProvider,
    private val configProvider: () -> SmartFrameConfig,
) : DisplayController {
    private val handler = Handler(Looper.getMainLooper())
    private val imageView = ImageView(context).apply {
        setBackgroundColor(Color.BLACK)
        scaleType = ImageView.ScaleType.CENTER_CROP
        keepScreenOn = true
    }
    private val statusText = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = STATUS_TEXT_SIZE_SP
        gravity = Gravity.CENTER
        text = context.getString(R.string.photo_source_loading)
        setPadding(STATUS_PADDING_DP.dp, 0, STATUS_PADDING_DP.dp, 0)
    }
    override val view: View = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        addView(imageView, matchParentLayoutParams())
        addView(statusText, matchParentLayoutParams())
    }
    private var started = false
    private var tasksActive = false
    private var refreshInProgress = false
    private var photos: List<DisplayImage> = emptyList()
    private val photoQueue = ArrayDeque<DisplayImage>()
    private var currentPhotoId: String? = null
    private val showNextPhoto = Runnable { displayNextPhoto() }
    private val pollPhotos = Runnable {
        refresh()
        schedulePhotoPoll()
    }

    /** 슬라이드쇼 작업을 시작합니다. Starts slideshow work. */
    override fun onStart() {
        started = true
        startTasks()
    }

    /** 슬라이드쇼 작업을 중지합니다. Stops slideshow work. */
    override fun onStop() {
        started = false
        stopTasks()
    }

    /** 화면이 꺼지면 네트워크·전환 작업을 중단합니다.
     * Stops network and transition work when the screen turns off. */
    override fun onScreenOff() = stopTasks()

    /** 화면이 켜진 경우 슬라이드쇼를 다시 시작합니다.
     * Restarts the slideshow when the screen turns on. */
    override fun onScreenOn() {
        if (started) startTasks()
    }

    /** 연결 가능 상태가 되면 즉시 원본을 새로 고칩니다.
     * Refreshes the source as soon as connectivity becomes available. */
    override fun onNetworkStateChanged(state: NetworkState) {
        if (tasksActive && state != NetworkState.UNAVAILABLE) refresh()
    }

    /** 공급자에서 이미지 목록을 새로 받아옵니다. Refreshes images from the provider. */
    override fun refresh() {
        if (refreshInProgress) return
        refreshInProgress = true
        imageProvider.refresh(context, configProvider()) { result ->
            refreshInProgress = false
            if (!started) return@refresh
            result.fold(
                onSuccess = { updatedPhotos ->
                    val oldIds = photos.mapTo(mutableSetOf(), DisplayImage::id)
                    val newIds = updatedPhotos.mapTo(mutableSetOf(), DisplayImage::id)
                    photos = updatedPhotos
                    if (oldIds != newIds) photoQueue.clear()
                    if (updatedPhotos.isEmpty()) {
                        statusText.apply {
                            text = context.getString(R.string.photo_source_has_no_photos)
                            visibility = View.VISIBLE
                        }
                    } else {
                        statusText.visibility = View.GONE
                        if (tasksActive && currentPhotoId == null) displayNextPhoto()
                    }
                },
                onFailure = { error ->
                    Log.w(TAG, "Could not refresh provider photos", error)
                    statusText.apply {
                        text = context.getString(R.string.photo_source_refresh_failed, error.message.orEmpty())
                        visibility = View.VISIBLE
                    }
                },
            )
        }
    }

    /** 보유한 콜백과 이미지 참조를 해제합니다. Releases callbacks and image references. */
    override fun destroy() = stopTasks()

    /** 주기 작업을 시작합니다. Starts periodic image work. */
    private fun startTasks() {
        if (!started) return
        tasksActive = true
        handler.removeCallbacks(showNextPhoto)
        handler.removeCallbacks(pollPhotos)
        refresh()
        if (currentPhotoId == null && photos.isNotEmpty()) displayNextPhoto()
        schedulePhotoPoll()
    }

    /** 주기 작업을 취소합니다. Cancels periodic image work. */
    private fun stopTasks() {
        tasksActive = false
        handler.removeCallbacks(showNextPhoto)
        handler.removeCallbacks(pollPhotos)
    }

    /** 다음 이미지를 무작위 큐에서 표시합니다. Displays the next image from a shuffled queue. */
    private fun displayNextPhoto() {
        handler.removeCallbacks(showNextPhoto)
        if (!tasksActive || photos.isEmpty()) return
        if (photoQueue.isEmpty()) {
            val shuffled = photos.shuffled().toMutableList()
            if (shuffled.size > 1 && shuffled.first().id == currentPhotoId) {
                shuffled += shuffled.removeAt(0)
            }
            photoQueue.addAll(shuffled)
        }
        val next = photoQueue.removeFirst()
        imageProvider.load(context, next, imageView.width, imageView.height) { result ->
            if (!tasksActive) return@load
            result.fold(
                onSuccess = { bitmap ->
                    imageView.alpha = 0f
                    imageView.setImageBitmap(bitmap)
                    imageView.animate().alpha(1f).setDuration(FADE_DURATION_MILLIS).start()
                    currentPhotoId = next.id
                    statusText.visibility = View.GONE
                },
                onFailure = { error ->
                    Log.w(TAG, "Could not load provider photo ${next.name}", error)
                    statusText.apply {
                        text = context.getString(R.string.photo_source_load_failed)
                        visibility = View.VISIBLE
                    }
                },
            )
            scheduleNextPhoto()
        }
    }

    /** 다음 이미지 전환을 예약합니다. Schedules the next image transition. */
    private fun scheduleNextPhoto() {
        if (tasksActive) handler.postDelayed(showNextPhoto, configProvider().photoIntervalSeconds * 1_000L)
    }

    /** 공급자 폴링을 예약합니다. Schedules a provider polling refresh. */
    private fun schedulePhotoPoll() {
        if (tasksActive) handler.postDelayed(pollPhotos, configProvider().pollIntervalMinutes * 60_000L)
    }

    /** 컨테이너 전체 크기 레이아웃을 생성합니다. Creates match-parent layout parameters. */
    private fun matchParentLayoutParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )

    private companion object {
        const val TAG = "ImageDisplay"
        const val FADE_DURATION_MILLIS = 600L
        const val STATUS_TEXT_SIZE_SP = 18f
        const val STATUS_PADDING_DP = 32
    }
}

/** dp를 픽셀로 변환합니다. Converts dp to physical pixels. */
private val Int.dp: Int
    get() = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
