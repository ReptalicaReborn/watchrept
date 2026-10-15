package com.watchrept.app

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Downloads an image and stores it in the watch's gallery (Pictures/WatchRept) via MediaStore. */
object ImageSaver {
    private val http = OkHttpClient()

    /** Returns the saved file name. */
    suspend fun save(context: Context, url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(url.startsWith("https://")) { "Only https images can be saved" }
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body ?: error("Empty response")
                val mime = body.contentType()
                    ?.let { "${it.type}/${it.subtype}" }
                    ?.takeIf { it.startsWith("image/") }
                    ?: "image/png"
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"
                val name = "WatchRept_${System.currentTimeMillis()}.$ext"

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/WatchRept")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create the file")
                try {
                    val out = resolver.openOutputStream(uri) ?: error("Could not open the file")
                    out.use { body.byteStream().copyTo(it) }
                    resolver.update(
                        uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null
                    )
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
                name
            }
        }
    }
}
