package com.dihcord.app.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/**
 * Plays soundboard sounds others trigger in our voice call, mixed into the call's audio. Everything to do
 * with the player (start, stop, its callbacks) runs on the main thread, one step after another, as
 * MediaPlayer needs; so a stop (deafen, leave) can never land between a check and a start.
 */
object SoundboardPlayer {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null // Main thread only.

    /** Plays [url] if [allowed] still says so when its turn comes. */
    fun play(url: String, volume: Double, allowed: () -> Boolean = { true }) {
        main.post {
            if (!allowed()) return@post
            release()
            runCatching {
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(url)
                    val v = volume.toFloat().coerceIn(0f, 1f)
                    setVolume(v, v)
                    // A newer sound or a stop may have replaced this one while it loaded.
                    setOnPreparedListener { mp -> if (player === mp && allowed()) mp.start() }
                    setOnCompletionListener { mp -> if (player === mp) release() }
                    setOnErrorListener { mp, _, _ -> if (player === mp) release() else runCatching { mp.release() }; true }
                    prepareAsync()
                }
            }.onFailure { release() }
        }
    }

    fun stop() {
        main.post { release() }
    }

    private fun release() {
        runCatching { player?.release() }
        player = null
    }
}
