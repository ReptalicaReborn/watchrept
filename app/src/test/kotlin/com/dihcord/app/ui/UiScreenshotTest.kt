package com.dihcord.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ScreenScaffold
import coil.imageLoader
import com.dihcord.app.R
import com.dihcord.app.api.DiscordMessage
import com.dihcord.app.api.DiscordUser
import com.dihcord.app.api.OnlineStatus
import com.dihcord.app.api.Reaction
import com.dihcord.app.api.ReactionEmoji
import com.dihcord.app.screens.ActionMenu
import com.dihcord.app.screens.DmRow
import com.dihcord.app.screens.DmsContent
import com.dihcord.app.screens.HomeContent
import com.dihcord.app.screens.HomeMention
import com.dihcord.app.screens.JUMP_TO_FIRST_MENTION
import com.dihcord.app.screens.MenuAction
import com.dihcord.app.screens.MessageBubble
import com.dihcord.app.screens.RecentDm
import com.dihcord.app.screens.TitledTimeText
import com.dihcord.app.screens.UserAvatar
import com.dihcord.app.screens.VoiceChannelContent
import com.dihcord.app.screens.VoiceMode
import com.dihcord.app.screens.VoiceTile
import com.dihcord.app.screens.VoiceTone
import com.dihcord.app.voice.VoiceCall
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the main screens with sample data on a 432x432 round watch (`./gradlew :app:testDebugUnitTest
 * --tests '*UiScreenshotTest*'`); the PNGs land in app/build/ui-shots. They are for looking at, not
 * compared against saved copies.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w216dp-h216dp-round-watch-xhdpi")
class UiScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = System.currentTimeMillis()
    private fun minsAgo(m: Int) = now - m * 60_000L

    private val recentDms = listOf(
        RecentDm("1", "Zaffox", null, OnlineStatus.ONLINE, mentionCount = 1, unread = true),
        RecentDm("2", "Mira Okafor", null, OnlineStatus.IDLE, mentionCount = 0, unread = false),
        RecentDm("3", "Game night", null, null, mentionCount = 0, unread = true),
    )

    private val mentions = listOf(
        HomeMention(
            "a", "1", "Zaffox", null, null, "Zaffox", null,
            "are you coming tonight? we start at 8", minsAgo(2), 1, "m1"
        ),
        HomeMention(
            "b", "10", "general", "g1", "Blockhaven", "Teodor", null,
            "@Willow can you check the server map before the reset", minsAgo(47), 3, JUMP_TO_FIRST_MENTION
        ),
    )

    private fun shotHome(voiceCall: VoiceCall? = null, mentions: List<HomeMention> = this.mentions, dms: List<RecentDm> = recentDms) =
        rule.watchShot(if (voiceCall != null) "home_in_call" else if (mentions.isEmpty()) "home_empty" else "home") {
            HomeContent(
                voiceCall = voiceCall,
                recentDms = dms,
                otherDmMentions = if (mentions.isEmpty()) 0 else 2,
                mentions = mentions,
                serverMentionCount = if (mentions.isEmpty()) 0 else 3,
                onOpenVoice = {}, onOpenDm = {}, onOpenDms = {}, onOpenServers = {},
                onOpenMention = {}, onDismissMention = {}, onOpenSettings = {}
            )
        }

    @Test fun home() = shotHome()
    @Test fun homeEmpty() = shotHome(mentions = emptyList(), dms = recentDms.take(2))
    @Test fun homeInCall() = shotHome(voiceCall = VoiceCall("g1", "v1", "Lobby", VoiceCall.Status.CONNECTED))

    private fun dm(
        id: String, name: String, status: OnlineStatus?, preview: String?, mins: Int,
        mentions: Int = 0, unread: Boolean = false, fallback: String? = null
    ) = DmRow(id, name, null, null, null, status, preview, fallback, minsAgo(mins), mentions, unread, hidden = false)

    @Test
    fun dms() = rule.watchShot("dms") {
        DmsContent(
            rows = listOf(
                dm("1", "Zaffox", OnlineStatus.ONLINE, "are you coming tonight? we start at 8", 2, mentions = 1, unread = true),
                dm("2", "Mira Okafor", OnlineStatus.IDLE, "You: ok see you there", 64),
                dm("3", "Game night", null, "Kai: lol that was close", 60 * 26, unread = true),
                dm("4", "Ines Duarte", OnlineStatus.DND, "Photo", 60 * 24 * 9),
            ),
            loading = false, hiddenCount = 2, showingHidden = false,
            onOpen = {}, onLongPress = {}, onToggleHidden = {}, onNewDm = {}
        )
    }

    @Test
    fun dmsLoading() = rule.watchShot("dms_loading") {
        DmsContent(
            rows = emptyList(), loading = true, hiddenCount = 0, showingHidden = false,
            onOpen = {}, onLongPress = {}, onToggleHidden = {}, onNewDm = {}
        )
    }

    @Test
    fun serverMenu() = rule.watchShot("server_menu") {
        ActionMenu(
            title = "Blockhaven",
            header = { UserAvatar("Blockhaven", null, 36.dp) },
            actions = listOf(
                MenuAction(R.drawable.unpin, "Unpin") {},
                MenuAction(R.drawable.mute, "Mute") {},
                MenuAction(R.drawable.hide, "Hide") {},
                MenuAction(R.drawable.down, "Earlier", flipIcon = true) {},
                MenuAction(R.drawable.down, "Later") {},
            ),
            onDismiss = {}
        )
    }

    private fun user(id: String, name: String) = DiscordUser(id, name.lowercase(), "0", name, null)

    @Test
    fun chat() = rule.watchShot("chat") {
        val mira = user("2", "Mira")
        val zaffox = user("1", "Zaffox")
        val me = user("9", "Willow")
        val ts = { m: Int -> java.time.Instant.ofEpochMilli(minsAgo(m)).toString() }
        val messages = listOf(
            DiscordMessage(
                "100", "c1", mira, "anyone up for **ranked**?", ts(9), null,
                reactions = listOf(
                    Reaction(ReactionEmoji(null, "🔥", false), 3, me = false),
                    Reaction(ReactionEmoji(null, "👍", false), 2, me = true)
                )
            ),
            DiscordMessage("101", "c1", mira, "need 1 more", ts(8), null),
            DiscordMessage(
                "102", "c1", zaffox, "<@9> you coming?", ts(1), null,
                mentionedUsers = listOf(me), mentionedUserIds = listOf("9")
            ),
        )
        ScreenScaffold(timeText = { TitledTimeText("#general") }) {
            Column(
                Modifier.fillMaxSize().padding(start = 22.dp, end = 18.dp, top = 30.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                messages.forEachIndexed { i, msg ->
                    MessageBubble(
                        msg = msg,
                        isContinuation = i == 1,
                        imageLoader = LocalContext.current.imageLoader,
                        myId = "9",
                        onReact = {},
                        onLongPress = {}
                    )
                }
            }
        }
    }

    private val people = listOf(
        VoiceTile("1", "Mira", null, speaking = true, muted = false, deafened = false),
        VoiceTile("2", "Zaffox", null, speaking = false, muted = false, deafened = false),
        VoiceTile("3", "Kai", null, speaking = false, muted = true, deafened = false),
    )

    private fun shotVoice(name: String, tiles: List<VoiceTile>, mode: VoiceMode, connectedAt: Long?, status: String? = null) =
        rule.watchShot(name) {
            VoiceChannelContent(
                channelName = "Lobby",
                status = status,
                statusTone = VoiceTone.QUIET,
                tiles = tiles,
                mode = mode,
                connectedAt = connectedAt,
                privacyCode = if (connectedAt != null) "48213 90577 13402" else null,
                onToggleMute = {}, onToggleDeafen = {}, onLeave = {}, onDecline = {}, onJoin = {}
            )
        }

    @Test fun voiceInCall() = shotVoice("voice_in_call", people, VoiceMode.Active(muted = false, deafened = false), connectedAt = now - 252_000)
    @Test fun voiceJoin() = shotVoice("voice_join", people.take(2).map { it.copy(speaking = false) }, VoiceMode.Idle("Join voice"), null)
    @Test fun voiceCrowd() = shotVoice(
        "voice_crowd",
        listOf("Ana", "Bo", "Chen", "Dara", "Eli", "Femi").mapIndexed { i, n -> VoiceTile("$i", n, null, i == 2, i == 4, false) },
        VoiceMode.Active(muted = true, deafened = false),
        connectedAt = now - 3_725_000
    )
}
