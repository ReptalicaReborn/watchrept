package com.dihcord.app

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Translates messages on the watch (ML Kit): the language is detected, the model for it is fetched once
 * over Wi-Fi (about 30 MB per language) and then works offline. Nothing is sent to a server.
 */
object Translator {
    data class Result(val text: String, val fromLanguage: String)

    class SameLanguageException : Exception("It's already in your language")
    class UnknownLanguageException : Exception("Couldn't tell what language this is")

    private suspend fun <T> awaitTask(task: com.google.android.gms.tasks.Task<T>): T =
        suspendCancellableCoroutine { cont ->
            task.addOnSuccessListener { cont.resume(it) }
            task.addOnFailureListener { cont.resumeWithException(it) }
        }

    /** Translates [text] into the watch's language. */
    suspend fun translate(text: String, target: String = java.util.Locale.getDefault().language): Result {
        val code = awaitTask(LanguageIdentification.getClient().identifyLanguage(text))
        if (code == "und") throw UnknownLanguageException()
        val source = TranslateLanguage.fromLanguageTag(code) ?: throw UnknownLanguageException()
        val to = TranslateLanguage.fromLanguageTag(target) ?: TranslateLanguage.ENGLISH
        if (source == to) throw SameLanguageException()
        val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(to).build())
        try {
            awaitTask(client.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()))
            return Result(awaitTask(client.translate(text)), java.util.Locale.forLanguageTag(code).displayLanguage)
        } finally {
            client.close()
        }
    }
}
