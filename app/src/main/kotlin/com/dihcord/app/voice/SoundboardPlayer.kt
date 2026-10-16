package com.dihcord.app.voice

import android.media.AudioAttributes
import android.media.MediaPlayer

/** Plays soundboard sounds others trigger in our voice call, mixed into the call's audio. */
object SoundboardPlayer {
    private var player: MediaPlayer? = null

    fun play(url: String, volume: Double) {
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
                setOnCompletionListener { it.release(); if (player === it) player = null }
                setOnErrorListener { mp, _, _ -> mp.release(); if (player === mp) player = null; true }
                prepareAsync()
            }
        }
    }

    fun stop() {
        runCatching { player?.release() }
        player = null
    }
}
