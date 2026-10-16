package com.dihcord.app.voice

import android.media.AudioAttributes
import android.media.MediaPlayer

/** Plays soundboard sounds others trigger in our voice call, mixed into the call's audio. */
object SoundboardPlayer {
    private var player: MediaPlayer? = null
    /** Starting and stopping take turns, so a stop (deafen, leave) can't land between a check and a start. */
    private val lock = Any()

    /** Plays [url] if [allowed] still says so once it's this call's turn. */
    fun play(url: String, volume: Double, allowed: () -> Boolean = { true }) = synchronized(lock) {
        if (!allowed()) return@synchronized
        runCatching {
            player?.release()
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
                setOnPreparedListener { it.start() }
                setOnCompletionListener { synchronized(lock) { it.release(); if (player === it) player = null } }
                setOnErrorListener { mp, _, _ -> synchronized(lock) { mp.release(); if (player === mp) player = null }; true }
                prepareAsync()
            }
        }
    }

    fun stop() = synchronized(lock) {
        runCatching { player?.release() }
        player = null
    }
}
