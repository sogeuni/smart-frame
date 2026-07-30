package dev.sogn.smartframe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneDriveGraphClientTest {
    @Test
    fun imageMimeTypeIsAccepted() {
        assertTrue(isSupportedImageFile("photo.bin", "image/jpeg"))
    }

    @Test
    fun commonImageExtensionsAreAcceptedWhenMimeTypeIsMissing() {
        assertTrue(isSupportedImageFile("photo.JPG", ""))
        assertTrue(isSupportedImageFile("photo.png", ""))
        assertTrue(isSupportedImageFile("photo.heic", "application/octet-stream"))
    }

    @Test
    fun nonImageFileIsRejected() {
        assertFalse(isSupportedImageFile("document.pdf", "application/pdf"))
    }
}
