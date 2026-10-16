package com.dihcord.app.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.dihcord.app.api.DiscordRepository
import com.dihcord.app.api.GatewayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** The voice call the app is in (at most one), as shown by the UI. */
data class VoiceCall(
    val guildId: String?,
    val channelId: String,
    val channelName: String,
    val status: Status,
    val error: String? = null,
    val muted: Boolean = false,
    val deafened: Boolean = false,
    val selfSpeaking: Boolean = false,
    /** User ids heard in the last moment. */
    val speaking: Set<String> = emptySet(),
    /** Discord's "voice privacy code" for the end-to-end encrypted session. */
    val privacyCode: String? = null,
    /** When the call first connected (epoch ms), for the call timer. */
    val connectedAt: Long? = null,
) {
    enum class Status { CONNECTING, CONNECTED, ENDED }
}

/**
 * Joins and leaves voice channels: the main-gateway handoff (op 4 -> VOICE_STATE_UPDATE + VOICE_SERVER_UPDATE),
 * a Wi-Fi/LTE network for the media, the [VoiceConnection], the [AudioEngine] and the foreground service.
 */
object VoiceManager {
    private const val TAG = "VoiceManager"

    private val _call = MutableStateFlow<VoiceCall?>(null)
    val call: StateFlow<VoiceCall?> = _call.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var repo: DiscordRepository? = null
    private var appContext: Context? = null
    @Volatile private var connection: VoiceConnection? = null
    @Volatile private var engine: AudioEngine? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val lastHeard = ConcurrentHashMap<String, Long>()

    val isInCall: Boolean get() = _call.value?.status.let { it == VoiceCall.Status.CONNECTING || it == VoiceCall.Status.CONNECTED }

    /** Joins a voice channel, or a DM/group call ([guildId] null); [ring] rings the others once connected. */
    fun join(
        context: Context,
        repository: DiscordRepository,
        guildId: String?,
        channelId: String,
        channelName: String,
        ring: Boolean = false,
    ) {
        if (_call.value?.channelId == channelId && isInCall) return
        leave()
        val ctx = context.applicationContext
        appContext = ctx
        repo = repository
        if (!NativeVoice.available) {
            _call.value = VoiceCall(guildId, channelId, channelName, VoiceCall.Status.ENDED, "Voice isn't included in this build.")
            return
        }
        _call.value = VoiceCall(guildId, channelId, channelName, VoiceCall.Status.CONNECTING)
        VoiceCallService.start(ctx)
        repository.keepConnected = true

        job = scope.launch {
            val myId = repository.currentUser.value?.id
            if (myId == null) {
                end("Not connected to Discord yet. Try again in a moment.")
                return@launch
            }
            val network = requestMediaNetwork(ctx)

            var sessionId: String? = null
            var server: GatewayEvent.VoiceServerUpdate? = null
            var currentChannel = channelId
            var currentGuild = guildId

            // Listen before asking, so the replies can't be missed.
            val listener = launch {
                repository.voiceEvents.collect { event ->
                    when (event) {
                        is GatewayEvent.VoiceStateUpdate -> {
                            val s = event.state
                            if (s.userId != myId) return@collect
                            if (s.channelId == null) {
                                // Left on another device, kicked, or the channel was deleted.
                                if (_call.value?.status != VoiceCall.Status.ENDED) end("Disconnected from voice.")
                                return@collect
                            }
                            sessionId = s.sessionId
                            if (s.channelId != currentChannel) {
                                // Moved by a moderator: a new server handoff follows.
                                currentChannel = s.channelId
                                currentGuild = s.guildId
                                val newChannel = s.channelId
                                _call.update { c ->
                                    c?.copy(channelId = newChannel, channelName = repository.channelNames.value[newChannel] ?: c.channelName)
                                }
                            }
                        }
                        is GatewayEvent.VoiceServerUpdate -> {
                            if (event.endpoint == null) return@collect
                            server = event
                            val sid = sessionId ?: return@collect
                            connect(event, sid, myId, currentGuild, currentChannel, network)
                        }
                        else -> {}
                    }
                    // The session id can arrive after the server update.
                    if (connection == null && server != null && sessionId != null) {
                        connect(server!!, sessionId!!, myId, currentGuild, currentChannel, network)
                    }
                }
            }

            repository.updateVoiceState(guildId, channelId, selfMute = false, selfDeaf = false)

            val handedOff = withTimeoutOrNull(15_000) {
                while (connection == null && isActive) delay(100)
                true
            }
            if (handedOff == null) {
                listener.cancel()
                end("Discord didn't let us join. You may lack permission, or the channel is full.")
                return@launch
            }
            // Starting a DM/group call: the call exists now, so ring everyone else.
            if (ring) launch { repository.ringCall(channelId).onFailure { Log.w(TAG, "ring failed", it) } }
            // Soundboard sounds played in this channel are heard here too (not while deafened).
            launch {
                repository.soundboardPlays.collect { e ->
                    val c = _call.value ?: return@collect
                    if (e.channelId == c.channelId && !c.deafened && e.userId != myId) {
                        SoundboardPlayer.play("https://cdn.discordapp.com/soundboard-sounds/${e.soundId}", e.volume)
                    }
                }
            }

            // Speaking rings: anyone heard in the last 400 ms.
            while (isActive) {
                val now = System.currentTimeMillis()
                val speaking = lastHeard.filterValues { now - it < 400 }.keys
                if (_call.value?.speaking != speaking) _call.update { it?.copy(speaking = speaking) }
                delay(200)
            }
        }
    }

    private fun connect(
        server: GatewayEvent.VoiceServerUpdate,
        sessionId: String,
        myId: String,
        guildId: String?,
        channelId: String,
        network: Network?,
    ) {
        connection?.close()
        engine?.stop()
        engine = null
        val endpoint = server.endpoint ?: return
        val voice = VoiceConnection(
            endpoint = endpoint,
            // Calls (DMs/groups) use the channel as the server id.
            serverId = server.guildId ?: server.channelId ?: guildId ?: channelId,
            channelId = channelId,
            userId = myId,
            sessionId = sessionId,
            token = server.token,
            network = network,
            listener = object : VoiceConnection.Listener {
                override fun onReady() {
                    val ctx = appContext ?: return
                    val audio = AudioEngine(
                        ctx,
                        onFrame = { frame ->
                            val c = connection ?: return@AudioEngine
                            if (frame != null) c.sendOpus(frame)
                            else {
                                // Five frames of silence avoid clicks on the other end, then stop "speaking".
                                repeat(5) { c.sendOpus(DaveSession.SILENCE_FRAME) }
                                c.setSpeaking(false)
                            }
                        },
                        onSelfSpeaking = { talking -> _call.update { it?.copy(selfSpeaking = talking) } },
                    )
                    audio.muted = _call.value?.muted == true
                    audio.deafened = _call.value?.deafened == true
                    engine = audio
                    audio.start()
                    _call.update { it?.copy(status = VoiceCall.Status.CONNECTED, error = null, connectedAt = it.connectedAt ?: System.currentTimeMillis()) }
                }

                override fun onClosed(code: Int, reason: String) {
                    if (connection == null) return
                    end(VoiceConnection.describeClose(code, reason))
                }

                override fun onAudio(userId: Long, opus: ByteArray) {
                    lastHeard[userId.toString()] = System.currentTimeMillis()
                    engine?.onRemoteFrame(userId, opus)
                }

                override fun onPrivacyCode(code: String) {
                    _call.update { it?.copy(privacyCode = code) }
                }
            },
        )
        connection = voice
        voice.connect()
    }

    fun toggleMute() {
        val call = _call.value ?: return
        // Unmuting while deafened also undeafens, like Discord.
        val muted = !call.muted
        val deafened = if (!muted) false else call.deafened
        apply(muted, deafened)
    }

    fun toggleDeafen() {
        val call = _call.value ?: return
        val deafened = !call.deafened
        apply(muted = deafened || call.muted, deafened = deafened)
    }

    private fun apply(muted: Boolean, deafened: Boolean) {
        val call = _call.value ?: return
        _call.value = call.copy(muted = muted, deafened = deafened)
        engine?.muted = muted
        engine?.deafened = deafened
        if (deafened) SoundboardPlayer.stop() // Deafened means hearing nothing, a sound already playing too.
        repo?.updateVoiceState(call.guildId, call.channelId, muted, deafened)
        appContext?.let { VoiceCallService.refresh(it) }
    }

    /** Hangs up (if in a call) and clears everything. */
    fun leave() {
        SoundboardPlayer.stop()
        val call = _call.value
        if (call != null && call.status != VoiceCall.Status.ENDED) {
            repo?.updateVoiceState(call.guildId, null, selfMute = false, selfDeaf = false)
        }
        teardown()
        _call.value = null
    }

    /** Forgets a call that ended (after the user saw why). */
    fun dismiss() {
        if (_call.value?.status == VoiceCall.Status.ENDED) _call.value = null
    }

    private fun end(reason: String) {
        Log.i(TAG, "call ended: $reason")
        SoundboardPlayer.stop()
        val call = _call.value
        if (call != null) repo?.updateVoiceState(call.guildId, null, selfMute = false, selfDeaf = false)
        teardown()
        _call.update { it?.copy(status = VoiceCall.Status.ENDED, error = reason, speaking = emptySet(), selfSpeaking = false) }
    }

    private fun teardown() {
        val j = job
        job = null
        connection?.let { c -> connection = null; c.close() }
        engine?.let { e -> engine = null; e.stop() }
        lastHeard.clear()
        repo?.keepConnected = false
        appContext?.let { ctx ->
            networkCallback?.let { cb -> runCatching { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
            networkCallback = null
            VoiceCallService.stop(ctx)
        }
        j?.cancel()
    }

    /**
     * Asks for Wi-Fi or mobile data. Watches usually route through the phone's Bluetooth link, which is slow and
     * doesn't carry the UDP voice traffic. Null when neither comes up in time (then the default network is tried).
     */
    private suspend fun requestMediaNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        return withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(network)
                    }
                }
                networkCallback = callback
                runCatching { cm.requestNetwork(request, callback) }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }
}
