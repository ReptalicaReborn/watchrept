package com.dihcord.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reads messages aloud with the watch's text-to-speech; one at a time, a new one replaces the last. */
object ReadAloud {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, String>? = null

    private val _speaking = MutableStateFlow<String?>(null)
    /** The id of the message being read, or null. */
    val speaking: StateFlow<String?> = _speaking.asStateFlow()

    /** Reads "[author] says [text]"; [id] identifies it for [speaking]. */
    fun speak(context: Context, id: String, author: String, text: String) {
        val line = "$author says: $text"
        val engine = tts
        if (engine != null && ready) {
            say(engine, id, line)
            return
        }
        pending = id to line
        if (engine == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                val t = tts ?: return@TextToSpeech
                if (!ready) { _speaking.value = null; return@TextToSpeech }
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { _speaking.value = utteranceId }
                    override fun onDone(utteranceId: String?) { if (_speaking.value == utteranceId) _speaking.value = null }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { _speaking.value = null }
                })
                pending?.let { (pid, pline) -> say(t, pid, pline) }
                pending = null
            }
        }
    }

    fun stop() {
        pending = null // So an engine still starting up doesn't read it afterwards.
        tts?.stop()
        _speaking.value = null
    }

    private fun say(engine: TextToSpeech, id: String, line: String) {
        _speaking.value = id
        engine.speak(line, TextToSpeech.QUEUE_FLUSH, null, id)
    }
}
