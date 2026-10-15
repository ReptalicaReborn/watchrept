package com.watchrept.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Guild(val id: String, val name: String)

@Serializable
data class Channel(val id: String, val name: String? = null, val type: Int = 0, val position: Int = 0)

@Serializable
data class User(
    val id: String,
    val username: String,
    @SerialName("global_name") val globalName: String? = null,
) {
    val displayName get() = globalName ?: username
}

@Serializable
data class Message(
    val id: String,
    @SerialName("channel_id") val channelId: String,
    val content: String = "",
    val author: User,
)

const val CHANNEL_TYPE_TEXT = 0
