package dev.sogn.moabom.image.onedrive

import android.content.Context
import android.graphics.Bitmap
import dev.sogn.moabom.SmartFrameConfig
import dev.sogn.moabom.image.DisplayImage
import dev.sogn.moabom.image.ImageProvider

/**
 * OneDrive 폴더의 이미지를 공통 ImageProvider 계약으로 제공하는 어댑터입니다.
 *
 * Adapts OneDrive folder images to the common ImageProvider contract.
 */
object OneDriveImageProvider : ImageProvider {
    /**
     * 선택된 OneDrive 폴더를 공통 표시 이미지 목록으로 변환합니다.
     * Converts the selected OneDrive folder into common display images.
     */
    override fun refresh(
        context: Context,
        config: SmartFrameConfig,
        callback: (Result<List<DisplayImage>>) -> Unit,
    ) {
        val folderId = config.oneDriveFolderId
        if (folderId.isBlank()) {
            callback(Result.failure(IllegalStateException("OneDrive folder is not selected")))
            return
        }
        OneDriveGraphClient.listPhotos(context, folderId) { result ->
            callback(result.map { photos -> photos.map(::OneDriveDisplayImage) })
        }
    }

    /**
     * OneDrive 전용 메타데이터가 든 이미지를 비트맵으로 비동기 로드합니다.
     * Asynchronously loads an image containing OneDrive-specific metadata as a bitmap.
     */
    override fun load(
        context: Context,
        image: DisplayImage,
        targetWidth: Int,
        targetHeight: Int,
        callback: (Result<Bitmap>) -> Unit,
    ) {
        val oneDriveImage = image as? OneDriveDisplayImage
        if (oneDriveImage == null) {
            callback(Result.failure(IllegalArgumentException("Image does not belong to OneDrive")))
            return
        }
        OneDrivePhotoLoader.load(
            context = context,
            photo = oneDriveImage.photo,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            callback = callback,
        )
    }
}

/**
 * OneDrive 전용 메타데이터를 공통 표시 이미지로 감쌉니다.
 * Wraps OneDrive-specific metadata in a common display image.
 */
private data class OneDriveDisplayImage(val photo: OneDrivePhoto) : DisplayImage {
    override val id: String = photo.id
    override val name: String = photo.name
}
