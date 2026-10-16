package com.dihcord.app.voice

import android.util.Log

/**
 * DAVE group state for one voice connection: a port of discord.js's DAVESession on top of [NativeVoice].
 * All methods are synchronized: the voice socket thread drives the protocol while the audio threads encrypt and
 * decrypt frames.
 */
class DaveSession(
    @Volatile var protocolVersion: Int,
    private val userId: Long,
    private val channelId: Long,
    /** Sends a new key package (binary opcode 26). */
    private val sendKeyPackage: (ByteArray) -> Unit,
    /** Tells the server a transition couldn't be applied (opcode 31). */
    private val sendInvalidCommitWelcome: (Int) -> Unit,
) {
    private var handle = 0L
    private val pendingTransitions = HashMap<Int, Int>()
    private var downgraded = false
    private var consecutiveFailures = 0
    private var reinitializing = false
    private var lastTransitionId: Int? = null

    @Synchronized
    fun reinit() {
        if (protocolVersion > 0) {
            if (handle != 0L) {
                if (!NativeVoice.daveReinit(handle, protocolVersion, userId, channelId)) log("reinit failed: ${NativeVoice.lastError()}")
            } else {
                handle = NativeVoice.daveCreate(protocolVersion, userId, channelId)
                if (handle == 0L) {
                    log("create failed: ${NativeVoice.lastError()}")
                    return
                }
            }
            NativeVoice.daveKeyPackage(handle)?.let(sendKeyPackage)
                ?: log("key package failed: ${NativeVoice.lastError()}")
        } else if (handle != 0L) {
            NativeVoice.daveReset(handle)
            NativeVoice.daveSetPassthroughMode(handle, true, TRANSITION_EXPIRY)
        }
    }

    @Synchronized
    fun setExternalSender(data: ByteArray) {
        if (handle != 0L && !NativeVoice.daveSetExternalSender(handle, data)) log("external sender: ${NativeVoice.lastError()}")
    }

    /** Returns whether to answer with TRANSITION_READY (opcode 23). */
    @Synchronized
    fun prepareTransition(transitionId: Int, version: Int): Boolean {
        pendingTransitions[transitionId] = version
        if (transitionId == 0) {
            executeTransition(0)
            return false
        }
        if (version == 0 && handle != 0L) NativeVoice.daveSetPassthroughMode(handle, true, TRANSITION_EXPIRY_PENDING_DOWNGRADE)
        return true
    }

    @Synchronized
    fun executeTransition(transitionId: Int): Boolean {
        val version = pendingTransitions.remove(transitionId) ?: return false
        val old = protocolVersion
        protocolVersion = version
        if (old != version && version == 0) {
            downgraded = true
        } else if (transitionId > 0 && downgraded) {
            downgraded = false
            if (handle != 0L) NativeVoice.daveSetPassthroughMode(handle, true, TRANSITION_EXPIRY)
        }
        reinitializing = false
        lastTransitionId = transitionId
        return true
    }

    @Synchronized
    fun prepareEpoch(epoch: Long, version: Int) {
        if (epoch == 1L) {
            protocolVersion = version
            reinit()
        }
    }

    /** Payload for opcode 28 (commit + optional welcome), or null when there's nothing to send. */
    @Synchronized
    fun processProposals(payload: ByteArray, connectedClients: Collection<Long>): ByteArray? {
        if (handle == 0L || payload.isEmpty()) return null
        val out = NativeVoice.daveProcessProposals(
            handle, payload[0].toInt() and 0xFF, payload.copyOfRange(1, payload.size), connectedClients.toLongArray()
        )
        if (out == null) log("proposals: ${NativeVoice.lastError()}")
        return out?.takeIf { it.isNotEmpty() }
    }

    /** Applies a commit (opcode 29); returns the transition id when it worked, else null. */
    @Synchronized
    fun processCommit(payload: ByteArray): Int? = applyTransition(payload) { h, body -> NativeVoice.daveProcessCommit(h, body) }

    /** Applies a welcome (opcode 30); returns the transition id when it worked, else null. */
    @Synchronized
    fun processWelcome(payload: ByteArray): Int? = applyTransition(payload) { h, body -> NativeVoice.daveProcessWelcome(h, body) }

    private fun applyTransition(payload: ByteArray, apply: (Long, ByteArray) -> Boolean): Int? {
        if (handle == 0L || payload.size < 2) return null
        val transitionId = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        return if (apply(handle, payload.copyOfRange(2, payload.size))) {
            if (transitionId == 0) {
                reinitializing = false
                lastTransitionId = 0
            } else {
                pendingTransitions[transitionId] = protocolVersion
            }
            transitionId
        } else {
            log("transition $transitionId failed: ${NativeVoice.lastError()}")
            recoverFromInvalidTransition(transitionId)
            null
        }
    }

    private fun recoverFromInvalidTransition(transitionId: Int) {
        if (reinitializing) return
        reinitializing = true
        consecutiveFailures = 0
        sendInvalidCommitWelcome(transitionId)
        reinit()
    }

    /** End-to-end encrypts an outgoing Opus frame (passes it through when DAVE isn't active yet). */
    @Synchronized
    fun encrypt(frame: ByteArray): ByteArray {
        if (protocolVersion == 0 || handle == 0L || !NativeVoice.daveIsReady(handle) || frame.contentEquals(SILENCE_FRAME)) return frame
        return NativeVoice.daveEncryptOpus(handle, frame) ?: frame
    }

    /** Decrypts an incoming frame from [userId]; null when it should be dropped. */
    @Synchronized
    fun decrypt(frame: ByteArray, userId: Long): ByteArray? {
        if (frame.contentEquals(SILENCE_FRAME) || handle == 0L) return frame
        val ready = NativeVoice.daveIsReady(handle)
        if (!ready || (protocolVersion == 0 && !NativeVoice.daveCanPassthrough(handle, userId))) return frame
        val out = NativeVoice.daveDecrypt(handle, userId, frame)
        if (out != null) {
            consecutiveFailures = 0
            return out
        }
        if (!reinitializing && pendingTransitions.isEmpty()) {
            consecutiveFailures++
            if (consecutiveFailures > FAILURE_TOLERANCE) lastTransitionId?.let { recoverFromInvalidTransition(it) }
        }
        return null
    }

    @Synchronized
    fun privacyCode(): String = if (handle == 0L || protocolVersion == 0) "" else NativeVoice.davePrivacyCode(handle)

    @Synchronized
    fun destroy() {
        if (handle != 0L) {
            NativeVoice.daveDestroy(handle)
            handle = 0L
        }
    }

    private fun log(message: String) = Log.w("DaveSession", message)

    companion object {
        /** Opus silence; never end-to-end encrypted. */
        val SILENCE_FRAME = byteArrayOf(0xF8.toByte(), 0xFF.toByte(), 0xFE.toByte())
        private const val TRANSITION_EXPIRY = 10
        private const val TRANSITION_EXPIRY_PENDING_DOWNGRADE = 24
        private const val FAILURE_TOLERANCE = 36
    }
}
