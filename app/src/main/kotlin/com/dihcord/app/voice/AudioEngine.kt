package com.dihcord.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.sqrt

/**
 * Microphone -> Opus -> [onFrame], and per-speaker Opus -> PCM -> mixed playback.
 * Mono 48 kHz in both directions (Opus decoders on the other end handle mono frames fine), 20 ms frames.
 */
class AudioEngine(
    private val context: Context,
    /** An encoded frame to send; null means "stopped talking" (send silence and clear the speaking flag). */
    private val onFrame: (ByteArray?) -> Unit,
    /** Whether we are currently transmitting (for the UI's own speaking ring). */
    private val onSelfSpeaking: (Boolean) -> Unit,
) {
    @Volatile var muted = false
    @Volatile var deafened = false

    @Volatile private var running = false
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var previousMode = AudioManager.MODE_NORMAL

    private class Speaker(val decoder: Long) {
        val queue = ConcurrentLinkedQueue<ShortArray>()
        @Volatile var lastHeard = 0L
    }
    private val speakers = ConcurrentHashMap<Long, Speaker>()

    fun start() {
        if (running) return
        running = true
        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        routeAudio()
        captureThread = Thread(::captureLoop, "voice-capture").apply { start() }
        playbackThread = Thread(::playbackLoop, "voice-playback").apply { start() }
    }

    fun stop() {
        running = false
        captureThread?.join(500)
        playbackThread?.join(500)
        speakers.values.forEach { NativeVoice.opusDecoderDestroy(it.decoder) }
        speakers.clear()
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
            audioManager.mode = previousMode
        }
    }

    /** Decodes a frame from [userId] and queues it for playback. Called on the network thread. */
    fun onRemoteFrame(userId: Long, opus: ByteArray) {
        if (!running || deafened) return
        val speaker = speakers.getOrPut(userId) {
            Speaker(NativeVoice.opusDecoderCreate(SAMPLE_RATE, 1))
        }
        if (speaker.decoder == 0L) return
        val pcm = ShortArray(MAX_FRAME)
        val n = synchronized(speaker) { NativeVoice.opusDecode(speaker.decoder, opus, pcm, MAX_FRAME, 1) }
        if (n <= 0) return
        // Split into 20 ms chunks so the mixer can take one per tick.
        var offset = 0
        while (offset < n) {
            val len = minOf(FRAME, n - offset)
            speaker.queue.add(pcm.copyOfRange(offset, offset + len).let { if (it.size == FRAME) it else it.copyOf(FRAME) })
            offset += len
        }
        // Cap the delay: a backlog over ~120 ms means we fell behind, so drop the oldest audio.
        while (speaker.queue.size > MAX_QUEUED) speaker.queue.poll()
        speaker.lastHeard = System.currentTimeMillis()
    }

    /** Prefer a Bluetooth headset if one is connected, otherwise the watch speaker. */
    private fun routeAudio() {
        if (Build.VERSION.SDK_INT < 31) {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
            return
        }
        runCatching {
            val devices = audioManager.availableCommunicationDevices
            val preferred = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            preferred?.let { audioManager.setCommunicationDevice(it) }
        }
    }

    @SuppressLint("MissingPermission") // checked before the call starts
    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 2 * 4)
            )
        } catch (e: Exception) {
            Log.e(TAG, "microphone unavailable", e)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "microphone failed to initialise")
            record.release()
            return
        }
        val effects = listOfNotNull(
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null,
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } else null,
        )
        val encoder = NativeVoice.opusEncoderCreate(SAMPLE_RATE, 1, BITRATE)
        if (encoder == 0L) {
            Log.e(TAG, "opus encoder: ${NativeVoice.lastError()}")
            record.release()
            return
        }
        val pcm = ShortArray(FRAME)
        var hangover = 0
        var talking = false
        try {
            record.startRecording()
            while (running) {
                var read = 0
                while (read < FRAME && running) {
                    val n = record.read(pcm, read, FRAME - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < FRAME) continue
                // Simple voice activity detection: transmit while loud enough, plus a short tail.
                val loud = !muted && rms(pcm) > VAD_THRESHOLD
                hangover = if (loud) HANGOVER_FRAMES else (hangover - 1).coerceAtLeast(0)
                val transmit = !muted && hangover > 0
                if (transmit) {
                    if (!talking) { talking = true; onSelfSpeaking(true) }
                    NativeVoice.opusEncode(encoder, pcm, FRAME)?.let(onFrame)
                } else if (talking) {
                    talking = false
                    onSelfSpeaking(false)
                    onFrame(null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
        } finally {
            runCatching { record.stop() }
            record.release()
            effects.forEach { runCatching { it.release() } }
            NativeVoice.opusEncoderDestroy(encoder)
            if (talking) onSelfSpeaking(false)
        }
    }

    private fun playbackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, FRAME * 2 * 3))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val mix = IntArray(FRAME)
        val out = ShortArray(FRAME)
        try {
            track.play()
            while (running) {
                mix.fill(0)
                var any = false
                for (speaker in speakers.values) {
                    val frame = speaker.queue.poll() ?: continue
                    any = true
                    for (i in 0 until FRAME) mix[i] += frame[i].toInt()
                }
                if (!any || deafened) {
                    // Nothing to play: keep the track fed with silence so timing stays steady.
                    out.fill(0)
                } else {
                    for (i in 0 until FRAME) out[i] = mix[i].coerceIn(-32768, 32767).toShort()
                }
                track.write(out, 0, FRAME) // blocks for ~20 ms once the buffer is full
            }
        } catch (e: Exception) {
            Log.e(TAG, "playback failed", e)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun rms(pcm: ShortArray): Double {
        var sum = 0.0
        for (s in pcm) sum += s.toDouble() * s
        return sqrt(sum / pcm.size)
    }

    companion object {
        private const val TAG = "AudioEngine"
        const val SAMPLE_RATE = 48_000
        const val FRAME = 960 // 20 ms
        private const val MAX_FRAME = 5760 // 120 ms, the longest Opus packet
        private const val MAX_QUEUED = 6
        private const val BITRATE = 32_000
        private const val VAD_THRESHOLD = 350.0
        private const val HANGOVER_FRAMES = 15 // 300 ms
    }
}
