package com.watchrept.app.data

import android.content.Context

class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("discord", Context.MODE_PRIVATE)

    var token: String?
        get() = prefs.getString(KEY, null)
        set(value) = prefs.edit().apply { if (value == null) remove(KEY) else putString(KEY, value) }.apply()

    private companion object {
        const val KEY = "bot_token"
    }
}
