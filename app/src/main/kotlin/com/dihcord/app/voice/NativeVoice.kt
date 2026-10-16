package com.dihcord.app.voice

import android.util.Log

/**
 * The native half of voice chat (`native/voice`, built by CI into libdihcord_voice.so): Discord's DAVE end-to-end
 * encryption (the davey library) and the Opus codec. Calls return null / false / negative on failure, with the
 * reason in [lastError]. Handles are owned by the caller and must be destroyed exactly once.
 */
object NativeVoice {
    /** Whether the library is present and loaded; voice chat is unavailable otherwise (e.g. a local build). */
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("dihcord_voice") }
            .onFailure { Log.e("NativeVoice", "Voice library unavailable", it) }
            .isSuccess
    }

    @JvmStatic external fun lastError(): String

    // DAVE
    @JvmStatic external fun daveMaxProtocolVersion(): Int
    @JvmStatic external fun daveCreate(protocolVersion: Int, userId: Long, channelId: Long): Long
    @JvmStatic external fun daveDestroy(handle: Long)
    @JvmStatic external fun daveReinit(handle: Long, protocolVersion: Int, userId: Long, channelId: Long): Boolean
    @JvmStatic external fun daveReset(handle: Long): Boolean
    @JvmStatic external fun daveIsReady(handle: Long): Boolean
    @JvmStatic external fun daveSetExternalSender(handle: Long, data: ByteArray): Boolean
    @JvmStatic external fun daveKeyPackage(handle: Long): ByteArray?
    /** Commit (+ welcome) to send as opcode 28; empty when there's nothing to send; null on error. */
    @JvmStatic external fun daveProcessProposals(handle: Long, operation: Int, proposals: ByteArray, expectedUserIds: LongArray?): ByteArray?
    @JvmStatic external fun daveProcessCommit(handle: Long, commit: ByteArray): Boolean
    @JvmStatic external fun daveProcessWelcome(handle: Long, welcome: ByteArray): Boolean
    @JvmStatic external fun daveEncryptOpus(handle: Long, frame: ByteArray): ByteArray?
    @JvmStatic external fun daveDecrypt(handle: Long, userId: Long, frame: ByteArray): ByteArray?
    @JvmStatic external fun daveCanPassthrough(handle: Long, userId: Long): Boolean
    @JvmStatic external fun daveSetPassthroughMode(handle: Long, passthrough: Boolean, expirySeconds: Int)
    @JvmStatic external fun davePrivacyCode(handle: Long): String

    // Opus
    @JvmStatic external fun opusEncoderCreate(sampleRate: Int, channels: Int, bitrate: Int): Long
    @JvmStatic external fun opusEncoderDestroy(handle: Long)
    @JvmStatic external fun opusEncode(handle: Long, pcm: ShortArray, frameSize: Int): ByteArray?
    @JvmStatic external fun opusDecoderCreate(sampleRate: Int, channels: Int): Long
    @JvmStatic external fun opusDecoderDestroy(handle: Long)
    /** Decodes [packet] (null conceals a lost one) into [pcm]; samples per channel, or < 0 on error. */
    @JvmStatic external fun opusDecode(handle: Long, packet: ByteArray?, pcm: ShortArray, frameSize: Int, channels: Int): Int
}
