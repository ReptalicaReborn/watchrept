package com.dihcord.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.UriHandler
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.dihcord.app.api.DiscordLink
import com.dihcord.app.api.InvitePreview
import java.util.concurrent.Executors

/**
 * Where tapped links go: Discord message/channel links open that chat in the app, invites open the
 * join screen, and other links open on the paired phone (or in the watch's browser, per Settings).
 */
class LinkHandler(
    private val context: Context,
    private val openChat: (channelId: String, guildId: String?, messageId: String?) -> Unit,
    private val openInvite: (code: String) -> Unit,
) : UriHandler {

    override fun openUri(uri: String) {
        DiscordLink.parse(uri)?.let { link ->
            openChat(link.channelId, link.guildId, link.messageId)
            return
        }
        if (isInvite(uri)) {
            InvitePreview.codeFrom(uri)?.let { openInvite(it); return }
        }
        if (FeaturePreferences.linksOnPhone(context)) openOnPhone(context, uri) else openOnWatch(context, uri)
    }

    companion object {
        private val INVITE = Regex("^(?:https?://)?(?:www\\.)?(?:discord\\.gg|discord(?:app)?\\.com/invite)/", RegexOption.IGNORE_CASE)
        private val executor by lazy { Executors.newSingleThreadExecutor() }

        fun isInvite(uri: String) = INVITE.containsMatchIn(uri)

        /** Opens [uri] in the phone's browser; falls back to the watch if no phone answers. */
        fun openOnPhone(context: Context, uri: String) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)
            val app = context.applicationContext
            runCatching {
                val future = RemoteActivityHelper(app, executor).startRemoteActivity(intent, null)
                Toast.makeText(app, "Opening on your phone", Toast.LENGTH_SHORT).show()
                future.addListener({
                    runCatching { future.get() }.onFailure {
                        android.os.Handler(android.os.Looper.getMainLooper()).post { openOnWatch(app, uri) }
                    }
                }, executor)
            }.onFailure { openOnWatch(app, uri) }
        }

        fun openOnWatch(context: Context, uri: String) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                Toast.makeText(context, "No app on the watch can open this link", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
