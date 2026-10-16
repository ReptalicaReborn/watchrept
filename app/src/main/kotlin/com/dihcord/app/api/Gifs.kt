package com.dihcord.app.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * A GIF from Discord's GIF picker. [url] is what gets posted (Discord embeds it); [preview] is a small image to
 * show in the picker, with [fallback] tried when the preview doesn't load. [preview] is null when there's nothing
 * the watch can draw (e.g. a non-Tenor video).
 */
data class Gif(val url: String, val preview: String?, val fallback: String?, val width: Int, val height: Int)

object Gifs {
    private val TENOR_MEDIA = Regex("""^(https://media\d*\.tenor\.com/[A-Za-z0-9_-]+?)AAA[A-Za-z0-9_-]{2}/([^/?#]+?)\.(?:mp4|webm|gif|png|webp)(?:[?#].*)?$""")
    private val GIPHY_MEDIA = Regex("""^(https://(?:media\d*|i)\.giphy\.com/media/[^/]+)/[^/?#]+\.(?:mp4|gif|webp)(?:[?#].*)?$""")

    /** A small animated GIF for a Tenor/Giphy media URL (any format), or null if [src] isn't one of theirs. */
    fun smallGif(src: String): String? {
        TENOR_MEDIA.find(src)?.let { return "${it.groupValues[1]}AAAAM/${it.groupValues[2]}.gif" }
        GIPHY_MEDIA.find(src)?.let { return "${it.groupValues[1]}/200w.gif" }
        return null
    }

    private fun isDrawable(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".gif") || path.endsWith(".png") || path.endsWith(".jpg") ||
                path.endsWith(".jpeg") || path.endsWith(".webp")
    }

    /** Preview and fallback for a GIF whose media is [src] (and optionally a known still/gif [alt]). */
    private fun previewFor(src: String, alt: String? = null): Pair<String?, String?> {
        val drawableAlt = alt?.takeIf(::isDrawable)
        val drawableSrc = src.takeIf(::isDrawable)
        val small = smallGif(src) ?: alt?.let(::smallGif)
        val full = drawableAlt ?: drawableSrc
        return if (small != null) small to full else full to null
    }

    /** Parses `/gifs/search` and `/gifs/trending-gifs` responses. */
    fun parseResults(body: String): List<Gif> {
        val arr = runCatching { JSONArray(body) }.getOrNull()
            ?: runCatching { JSONObject(body).optJSONArray("gifs") }.getOrNull()
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val src = o.optString("src")
            val gifSrc = o.optString("gif_src").takeIf { it.isNotEmpty() } ?: o.optString("preview").takeIf { it.isNotEmpty() }
            val (preview, fallback) = previewFor(src, gifSrc)
            Gif(url, preview, fallback, o.optInt("width"), o.optInt("height"))
        }
    }

    /**
     * Favourite GIFs from the base64 `FrecencyUserSettings` proto (`/users/@me/settings-proto/2`):
     * field 2 `favorite_gifs` -> field 1 `map<string url, FavoriteGIF>`, where FavoriteGIF is
     * {1 format, 2 src, 3 width, 4 height, 5 order}. Newest first.
     */
    fun parseFavorites(settings: ByteArray): List<Gif> {
        val favorites = Proto.fields(settings).lastOrNull { it.number == 2 && it.bytes != null }?.bytes ?: return emptyList()
        return Proto.fields(favorites)
            .filter { it.number == 1 && it.bytes != null }
            .mapNotNull { entry ->
                val parts = Proto.fields(entry.bytes!!)
                val url = parts.firstOrNull { it.number == 1 }?.bytes?.toString(Charsets.UTF_8) ?: return@mapNotNull null
                val value = parts.firstOrNull { it.number == 2 }?.bytes?.let(Proto::fields).orEmpty()
                val src = value.firstOrNull { it.number == 2 }?.bytes?.toString(Charsets.UTF_8).orEmpty()
                val width = value.firstOrNull { it.number == 3 }?.varint?.toInt() ?: 0
                val height = value.firstOrNull { it.number == 4 }?.varint?.toInt() ?: 0
                val order = value.firstOrNull { it.number == 5 }?.varint ?: 0L
                val (preview, fallback) = previewFor(src.ifEmpty { url })
                order to Gif(url, preview, fallback, width, height)
            }
            .sortedByDescending { it.first }
            .map { it.second }
    }
}

/** Just enough protobuf wire-format reading for Discord's settings protos. */
internal object Proto {
    class Field(val number: Int, val varint: Long?, val bytes: ByteArray?)

    /** Top-level fields of a message; stops quietly at anything malformed. */
    fun fields(data: ByteArray): List<Field> {
        val out = mutableListOf<Field>()
        var pos = 0
        fun readVarint(): Long? {
            var result = 0L
            var shift = 0
            while (pos < data.size && shift < 64) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return null
        }
        while (pos < data.size) {
            val key = readVarint() ?: break
            val number = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> out += Field(number, readVarint() ?: break, null)
                1 -> { if (pos + 8 > data.size) break; pos += 8 }
                2 -> {
                    val len = readVarint() ?: break
                    if (len < 0 || pos + len > data.size) break
                    out += Field(number, null, data.copyOfRange(pos, pos + len.toInt()))
                    pos += len.toInt()
                }
                5 -> { if (pos + 4 > data.size) break; pos += 4 }
                else -> break
            }
        }
        return out
    }
}
