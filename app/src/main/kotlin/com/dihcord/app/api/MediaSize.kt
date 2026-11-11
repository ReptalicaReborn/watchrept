package com.dihcord.app.api

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Discord's media proxy resizes pictures on request. A watch shows a photo about 250 pixels wide, so asking for
 * that instead of the original (often several megabytes from a phone camera) saves most of the download, the
 * decoding and the disk cache - which matters on a watch reaching the internet through a phone.
 */
object MediaSize {
    /** Long side, in pixels, of the copy loaded when a picture is opened full screen. */
    const val VIEWER_PX = 960

    /** Only these hosts resize; anywhere else the extra query would be ignored or, worse, break a signed link. */
    private fun resizes(url: String): Boolean {
        val host = url.substringAfter("://", "").substringBefore('/').lowercase()
        return host == "media.discordapp.net" || (host.startsWith("images-ext-") && host.endsWith(".discordapp.net"))
    }

    private fun isAnimated(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        // Re-encoding would keep only the first frame, so these are resized in their own format.
        return path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".apng")
    }

    private fun with(url: String, query: String) = url + (if ('?' in url) "&" else "?") + query

    /**
     * [proxyUrl] asking for a copy at most [maxPx] on its longer side. Unchanged when the picture is already that
     * small, its size isn't known (the proportions couldn't be kept), or the host doesn't resize.
     */
    fun fit(proxyUrl: String, width: Int?, height: Int?, maxPx: Int): String {
        if (width == null || height == null || width <= 0 || height <= 0 || maxPx <= 0) return proxyUrl
        if (!resizes(proxyUrl) || max(width, height) <= maxPx) return proxyUrl
        val scale = maxPx.toFloat() / max(width, height)
        val size = "width=${max(1, (width * scale).roundToInt())}&height=${max(1, (height * scale).roundToInt())}"
        return with(proxyUrl, if (isAnimated(proxyUrl)) size else "format=webp&$size")
    }

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
