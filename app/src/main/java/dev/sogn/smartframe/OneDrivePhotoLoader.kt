package dev.sogn.smartframe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object OneDrivePhotoLoader {
    private const val MAX_CACHE_BYTES = 200L * 1024L * 1024L
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)

    fun load(
        context: Context,
        photo: OneDrivePhoto,
        targetWidth: Int,
        targetHeight: Int,
        callback: (Result<Bitmap>) -> Unit,
    ) {
        val appContext = context.applicationContext
        val loadWithToken: (String?) -> Unit = { accessToken ->
            executor.execute {
                val result = runCatching {
                    val cacheDirectory = File(appContext.cacheDir, "onedrive_photos").apply {
                        mkdirs()
                    }
                    val cacheFile = File(cacheDirectory, cacheKey(photo))
                    if (!cacheFile.isFile) {
                        val directUrl = photo.downloadUrl
                        if (directUrl != null) {
                            downloadDirect(directUrl, cacheFile)
                        } else {
                            downloadFromGraph(
                                itemId = photo.id,
                                accessToken = requireNotNull(accessToken),
                                destination = cacheFile,
                            )
                        }
                        trimCache(cacheDirectory, cacheFile)
                    } else {
                        cacheFile.setLastModified(System.currentTimeMillis())
                    }
                    decodeSampled(cacheFile, targetWidth, targetHeight)
                }
                Handler(context.mainLooper).post { callback(result) }
            }
        }
        if (photo.downloadUrl != null) {
            loadWithToken(null)
        } else {
            OneDriveAuthManager.acquireAccessToken(appContext) { tokenResult ->
                tokenResult.fold(
                    onSuccess = loadWithToken,
                    onFailure = { callback(Result.failure(it)) },
                )
            }
        }
    }

    private fun downloadFromGraph(
        itemId: String,
        accessToken: String,
        destination: File,
    ) {
        val url = "https://graph.microsoft.com/v1.0/me/drive/items/" +
            "${Uri.encode(itemId)}/content"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            val status = connection.responseCode
            val redirectUrl = connection.getHeaderField("Location")
            when {
                status in 300..399 && !redirectUrl.isNullOrBlank() ->
                    downloadDirect(redirectUrl, destination)
                status in 200..299 ->
                    copyResponse(connection, destination)
                else -> throw IOException("Microsoft Graph image HTTP $status")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadDirect(url: String, destination: File) {
        val temporary = File(destination.parentFile, "${destination.name}.part")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("OneDrive image HTTP $status")
            }
            copyResponse(connection, destination)
        } finally {
            connection.disconnect()
            if (temporary.exists() && !destination.exists()) {
                temporary.delete()
            }
        }
    }

    private fun copyResponse(connection: HttpURLConnection, destination: File) {
        val temporary = File(destination.parentFile, "${destination.name}.part")
        try {
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use(input::copyTo)
            }
            check(temporary.renameTo(destination)) {
                "Could not move downloaded image into the cache"
            }
        } finally {
            if (temporary.exists() && !destination.exists()) {
                temporary.delete()
            }
        }
    }

    private fun decodeSampled(file: File, targetWidth: Int, targetHeight: Int): Bitmap {
        val safeWidth = targetWidth.coerceAtLeast(1)
        val safeHeight = targetHeight.coerceAtLeast(1)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sampleSize = 1
        while (
            bounds.outWidth / (sampleSize * 2) >= safeWidth &&
            bounds.outHeight / (sampleSize * 2) >= safeHeight
        ) {
            sampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            },
        )
        if (bitmap == null) {
            file.delete()
            throw IOException("Unsupported or damaged image: ${file.name}")
        }
        return bitmap
    }

    private fun cacheKey(photo: OneDrivePhoto): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("${photo.id}:${photo.eTag}".toByteArray())
        return bytes.joinToString(separator = "") { "%02x".format(it) } + ".img"
    }

    private fun trimCache(directory: File, protectedFile: File) {
        val files = directory.listFiles()
            ?.filter { it.isFile && it != protectedFile && !it.name.endsWith(".part") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        var totalBytes = directory.listFiles()?.sumOf(File::length) ?: return
        for (file in files) {
            if (totalBytes <= MAX_CACHE_BYTES) break
            val length = file.length()
            if (file.delete()) totalBytes -= length
        }
    }
}
