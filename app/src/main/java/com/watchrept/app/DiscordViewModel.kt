package com.watchrept.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchrept.app.data.Channel
import com.watchrept.app.data.DiscordApi
import com.watchrept.app.data.Gateway
import com.watchrept.app.data.Guild
import com.watchrept.app.data.Message
import com.watchrept.app.data.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

data class UiState(
    val loggedIn: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val guilds: List<Guild> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val messages: List<Message> = emptyList(),
)

class DiscordViewModel(app: Application) : AndroidViewModel(app) {
    private val store = TokenStore(app)
    private val http = OkHttpClient()
    private var api: DiscordApi? = null
    private var gateway: Gateway? = null
    private var currentChannel: String? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        store.token?.let { connect(it, store.isBot) }
    }

    fun login(token: String, isBot: Boolean) {
        val t = token.trim()
        if (t.isEmpty()) return
        store.token = t
        store.isBot = isBot
        connect(t, isBot)
    }

    fun logout() {
        gateway?.stop()
        gateway = null
        api = null
        store.token = null
        _state.value = UiState()
    }

    private fun connect(token: String, isBot: Boolean) {
        api = DiscordApi(token, isBot, http)
        gateway?.stop()
        gateway = Gateway(token, isBot, http, viewModelScope).also { gw ->
            gw.start()
            viewModelScope.launch {
                gw.messages.collect { m ->
                    if (m.channelId == currentChannel) {
                        _state.update { it.copy(messages = (it.messages + m).takeLast(MAX_MESSAGES)) }
                    }
                }
            }
        }
        _state.update { it.copy(loggedIn = true) }
        loadGuilds()
    }

    fun loadGuilds() = load { _state.update { s -> s.copy(guilds = api!!.guilds()) } }

    fun openGuild(id: String) {
        _state.update { it.copy(channels = emptyList()) }
        load { _state.update { s -> s.copy(channels = api!!.textChannels(id)) } }
    }

    fun openChannel(id: String) {
        currentChannel = id
        _state.update { it.copy(messages = emptyList()) }
        load { _state.update { s -> s.copy(messages = api!!.messages(id)) } }
    }

    fun closeChannel() {
        currentChannel = null
    }

    fun send(text: String) {
        val channel = currentChannel ?: return
        if (text.isBlank()) return
        load { api!!.send(channel, text.trim()) }
    }

    private fun load(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                block()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Error") }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    override fun onCleared() {
        gateway?.stop()
    }

    private companion object {
        const val MAX_MESSAGES = 50
    }
}
