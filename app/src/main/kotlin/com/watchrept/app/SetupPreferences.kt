package com.watchrept.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import androidx.core.content.edit

/** Encrypts the token with an AES-GCM key held in the Android Keystore, so it never sits on disk in plaintext. */
private object TokenCipher {
    private const val ALIAS = "watchrept_token_key"
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Returns null if the value is corrupt or the key was lost (e.g. keystore reset); the user just logs in again. */
    fun decrypt(encoded: String): String? = runCatching {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12))) }
        String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }.getOrNull()
}

object SetupPreferences {
    private const val PREFS_NAME = "discord_wear_prefs"
    private const val KEY_TOKEN = "discord_token_enc"
    private const val KEY_HIDE_INACCESSIBLE = "hide_inaccessible_channels"
    private const val KEY_SEND_ANIMATED_AS_GIF = "send_animated_emoji_as_gif"
    private const val KEY_SPOILER_REVEAL_ON_TAP = "spoiler_reveal_on_tap"
    private const val KEY_SHOW_MENTION_BADGES = "show_mention_badges"
    private const val KEY_COMPACT_MODE = "compact_mode"

    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_TOKEN, TokenCipher.encrypt(token)) }
    }

    fun getToken(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)
            ?.let { TokenCipher.decrypt(it) }

    fun clearToken(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_TOKEN) }
    }

    fun getHideInaccessibleChannels(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_INACCESSIBLE, true)

    fun setHideInaccessibleChannels(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_HIDE_INACCESSIBLE, hide) }
    }

    fun getSendAnimatedAsGif(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SEND_ANIMATED_AS_GIF, true)

    fun setSendAnimatedAsGif(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_SEND_ANIMATED_AS_GIF, value) }
    }

    fun getSpoilerRevealOnTap(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SPOILER_REVEAL_ON_TAP, true)

    fun setSpoilerRevealOnTap(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_SPOILER_REVEAL_ON_TAP, value) }
    }

    fun getShowMentionBadges(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_MENTION_BADGES, true)

    fun setShowMentionBadges(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_SHOW_MENTION_BADGES, value) }
    }

    fun getCompactMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPACT_MODE, false)

    fun setCompactMode(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_COMPACT_MODE, value) }
    }

    private const val KEY_NOTIFICATIONS = "background_notifications"

    fun getNotificationsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIFICATIONS, false)

    fun setNotificationsEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_NOTIFICATIONS, value) }
    }

    private const val KEY_QUIET_ENABLED = "quiet_hours_enabled"
    private const val KEY_QUIET_START = "quiet_hours_start"
    private const val KEY_QUIET_END = "quiet_hours_end"
    private const val KEY_MUTED_CHANNELS = "muted_channels"

    fun getQuietHoursEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_QUIET_ENABLED, false)

    fun setQuietHoursEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putBoolean(KEY_QUIET_ENABLED, value) }
    }

    fun getQuietStartHour(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_QUIET_START, 22)

    fun getQuietEndHour(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_QUIET_END, 7)

    fun setQuietHours(context: Context, startHour: Int, endHour: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_QUIET_START, startHour.mod(24))
            putInt(KEY_QUIET_END, endHour.mod(24))
        }
    }

    fun isQuietNow(context: Context): Boolean =
        getQuietHoursEnabled(context) && QuietHours.isQuiet(
            hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
            startHour = getQuietStartHour(context),
            endHour = getQuietEndHour(context),
        )

    fun getMutedChannels(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_MUTED_CHANNELS, emptySet()) ?: emptySet()

    fun muteChannel(context: Context, channelId: String) {
        val updated = getMutedChannels(context) + channelId
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putStringSet(KEY_MUTED_CHANNELS, updated) }
    }

    fun clearMutedChannels(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { remove(KEY_MUTED_CHANNELS) }
    }

    fun isSetupComplete(context: Context): Boolean = getToken(context) != null
    private const val KEY_PINNED_SERVERS = "pinned_servers"

    fun getPinnedServers(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_PINNED_SERVERS, emptySet()) ?: emptySet()

    fun setPinnedServers(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putStringSet(KEY_PINNED_SERVERS, ids) }
    }

    fun togglePinnedServer(context: Context, guildId: String): Boolean {
        val current = getPinnedServers(context).toMutableSet()
        val pinned = if (current.contains(guildId)) {
            current.remove(guildId); false
        } else {
            current.add(guildId); true
        }
        setPinnedServers(context, current)
        return pinned
    }

    private const val KEY_HIDDEN_SERVERS = "hidden_servers"

    fun getHiddenServers(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_HIDDEN_SERVERS, emptySet()) ?: emptySet()

    fun setHiddenServers(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putStringSet(KEY_HIDDEN_SERVERS, ids) }
    }

    fun toggleHiddenServer(context: Context, guildId: String): Boolean {
        val current = getHiddenServers(context).toMutableSet()
        val hidden = if (current.contains(guildId)) {
            current.remove(guildId); false
        } else {
            current.add(guildId); true
        }
        setHiddenServers(context, current)
        return hidden
    }

    private const val KEY_HIDDEN_DMS = "hidden_dms"

    fun getHiddenDms(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_HIDDEN_DMS, emptySet()) ?: emptySet()

    fun setHiddenDms(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putStringSet(KEY_HIDDEN_DMS, ids) }
    }

    fun toggleHiddenDm(context: Context, channelId: String): Boolean {
        val current = getHiddenDms(context).toMutableSet()
        val hidden = if (current.contains(channelId)) {
            current.remove(channelId); false
        } else {
            current.add(channelId); true
        }
        setHiddenDms(context, current)
        return hidden
    }

    fun clearDiscordCache(context: Context) {
        val cache = context.getSharedPreferences("discord_wear_cache", Context.MODE_PRIVATE)
        cache.edit { clear() }
    }

    fun clearAll(context: Context) {
        clearToken(context)
        clearDiscordCache(context)
    }
}
