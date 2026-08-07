package dev.sogn.moabom.image

import android.content.Context
import android.graphics.Bitmap
import dev.sogn.moabom.DisplayMode
import dev.sogn.moabom.SmartFrameConfig
import dev.sogn.moabom.image.onedrive.OneDriveImageProvider

/**
 * 화면에 표시할 이미지의 공급자 독립 식별자입니다.
 *
 * An identifier for an image that can be displayed independently of its provider.
 */
interface DisplayImage {
    val id: String
    val name: String
}

/**
 * 로컬 및 클라우드 이미지 원본을 화면 계층에 제공하는 공통 계약입니다.
 *
 * A common contract that supplies local or cloud images to the display layer.
 */
interface ImageProvider {
    /**
     * 현재 설정의 이미지 목록을 비동기로 새로 고칩니다.
     * Refreshes the current configuration's image list asynchronously.
     */
    fun refresh(
        context: Context,
        config: SmartFrameConfig,
        callback: (Result<List<DisplayImage>>) -> Unit,
    )

    /**
     * 공급자 전용 이미지를 화면 크기에 맞는 비트맵으로 비동기 변환합니다.
     * Converts a provider-specific image to a display-sized bitmap asynchronously.
     */
    fun load(
        context: Context,
        image: DisplayImage,
        targetWidth: Int,
        targetHeight: Int,
        callback: (Result<Bitmap>) -> Unit,
    )
}

/**
 * 표시 방식과 실제 이미지 공급자를 연결합니다.
 *
 * Maps a display mode to its concrete image provider. Add new providers here
 * without adding provider-specific logic to MainActivity.
 */
object ImageProviderRegistry {
    /**
     * 표시 방식을 처리할 공급자를 반환하며 웹 방식에는 공급자가 필요 없습니다.
     * Returns the provider for a display mode; WebView mode needs no image provider.
     */
    fun providerFor(displayMode: DisplayMode): ImageProvider? = when (displayMode) {
        DisplayMode.WEBVIEW -> null
        DisplayMode.IMAGE -> OneDriveImageProvider
    }
}
