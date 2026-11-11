package com.dihcord.app.api

import kotlin.math.max
import kotlin.math.roundToInt

/** Thumbnails for videos, made by Discord's media proxy. Pictures are always loaded as their originals. */
object MediaSize {
    /** Only these hosts make thumbnails; anywhere else the extra query would be ignored or break a signed link. */
    private fun resizes(url: String): Boolean {
        val host = url.substringAfter("://", "").substringBefore('/').lowercase()
        return host == "media.discordapp.net" || (host.startsWith("images-ext-") && host.endsWith(".discordapp.net"))
    }

    private fun with(url: String, query: String) = url + (if ('?' in url) "&" else "?") + query

    /**
     * A still picture of a video attachment, at most [maxPx] on its longer side; null when the host can't make one.
     * Without it, drawing a video's thumbnail means downloading the video.
     */
    fun poster(proxyUrl: String, width: Int?, height: Int?, maxPx: Int): String? {
        if (!resizes(proxyUrl)) return null
        if (width == null || height == null || width <= 0 || height <= 0 || maxPx <= 0) return with(proxyUrl, "format=webp")
        val scale = minOf(1f, maxPx.toFloat() / max(width, height))
        return with(proxyUrl, "format=webp&width=${max(1, (width * scale).roundToInt())}&height=${max(1, (height * scale).roundToInt())}")
    }
}
