package dev.sogn.moabom.image

import dev.sogn.moabom.DisplayMode
import dev.sogn.moabom.image.onedrive.OneDriveImageProvider
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Verifies that display modes stay decoupled from concrete image providers. */
class ImageProviderRegistryTest {
    @Test
    fun webViewDoesNotRequireAnImageProvider() {
        assertNull(ImageProviderRegistry.providerFor(DisplayMode.WEBVIEW))
    }

    @Test
    fun oneDriveUsesTheOneDriveProvider() {
        assertSame(OneDriveImageProvider, ImageProviderRegistry.providerFor(DisplayMode.IMAGE))
    }
}
