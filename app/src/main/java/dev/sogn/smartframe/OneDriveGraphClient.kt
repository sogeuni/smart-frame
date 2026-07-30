package dev.sogn.smartframe

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class OneDriveFolder(
    val id: String,
    val name: String,
)

data class OneDrivePhoto(
    val id: String,
    val name: String,
    val downloadUrl: String?,
    val eTag: String,
)

object OneDriveGraphClient {
    private const val GRAPH_ROOT = "https://graph.microsoft.com/v1.0"
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun listFolders(
        context: Context,
        parentId: String?,
        callback: (Result<List<OneDriveFolder>>) -> Unit,
    ) {
        withToken(context, callback) { token ->
            val url = if (parentId == null) {
                "$GRAPH_ROOT/me/drive/root/children"
            } else {
                "$GRAPH_ROOT/me/drive/items/${Uri.encode(parentId)}/children"
            }
            pagedItems(
                "$url?\$select=id,name,folder&\$orderby=name",
                token,
            ).filter { it.has("folder") }.map { item ->
                OneDriveFolder(
                    id = item.getString("id"),
                    name = item.getString("name"),
                )
            }
        }
    }

    fun listPhotos(
        context: Context,
        folderId: String,
        callback: (Result<List<OneDrivePhoto>>) -> Unit,
    ) {
        withToken(context, callback) { token ->
            val items = pagedItems(
                "$GRAPH_ROOT/me/drive/items/${Uri.encode(folderId)}/children" +
                    "?\$select=id,name,eTag,file,@microsoft.graph.downloadUrl",
                token,
            )
            items.mapNotNull { item ->
                if (!item.has("file")) return@mapNotNull null
                val mimeType = item.optJSONObject("file")?.optString("mimeType").orEmpty()
                val name = item.getString("name")
                if (!isSupportedImageFile(name, mimeType)) {
                    null
                } else {
                    OneDrivePhoto(
                        id = item.getString("id"),
                        name = name,
                        downloadUrl = item.optString("@microsoft.graph.downloadUrl")
                            .takeIf(String::isNotBlank),
                        eTag = item.optString("eTag"),
                    )
                }
            }
        }
    }

    private fun <T> withToken(
        context: Context,
        callback: (Result<T>) -> Unit,
        block: (String) -> T,
    ) {
        OneDriveAuthManager.acquireAccessToken(context) { tokenResult ->
            tokenResult.fold(
                onSuccess = { token ->
                    executor.execute {
                        val result = runCatching { block(token) }
                        android.os.Handler(context.mainLooper).post { callback(result) }
                    }
                },
                onFailure = { callback(Result.failure(it)) },
            )
        }
    }

    private fun pagedItems(initialUrl: String, token: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        var nextUrl: String? = initialUrl
        while (nextUrl != null) {
            val response = getJson(nextUrl, token)
            val values = response.getJSONArray("value")
            repeat(values.length()) { index ->
                result += values.getJSONObject(index)
            }
            nextUrl = response.optString("@odata.nextLink").takeIf(String::isNotBlank)
        }
        return result
    }

    private fun getJson(url: String, token: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                val message = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull()
                throw IOException("Microsoft Graph HTTP $status: ${message.orEmpty()}")
            }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}

internal fun isSupportedImageFile(name: String, mimeType: String): Boolean {
    if (mimeType.startsWith("image/", ignoreCase = true)) return true
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
    return extension in setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp")
}
