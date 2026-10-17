package com.dihcord.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import coil.imageLoader
import com.dihcord.app.DihcordApp
import com.dihcord.app.FeaturePreferences
import com.dihcord.app.api.*
import com.dihcord.app.screens.*
import org.json.JSONObject
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshots of the screens added by the feature list ("feat_" prefix in app/build/ui-shots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w216dp-h216dp-round-watch-xhdpi")
class NewFeaturesShotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app get() = RuntimeEnvironment.getApplication() as DihcordApp

    @Before fun seed() = app.useRepositoryForTests(SampleData.repository())
    @After fun clear() = app.useRepositoryForTests(null)

    private fun shot(name: String, swipes: Int = 0, content: @androidx.compose.runtime.Composable () -> Unit) =
        rule.watchShot("feat_$name", settleMs = 600, swipes = swipes, content = content)

    private val msgs = SampleData.generalMessages + SampleData.zaffoxMessages

    @Test fun pins() = shot("pins") { PinnedContent("#general", msgs.take(3), null, SampleData.me.id, {}, {}) }
    @Test fun search() = shot("search") { SearchContent("#general", "castle", {}, SampleData.zaffoxMessages.take(3), null, SampleData.me.id, {}, {}) }
    @Test fun searchIndexing() = shot("search_indexing") { SearchContent("#general", "castle", {}, emptyList(), DiscordRestClient.SearchNotReadyException(), null, {}, {}) }
    @Test fun saved() = shot("saved") {
        SavedContent(
            listOf(
                SavedMessage(SampleData.id(60), "802", "700", "general", "Teodor", "server reset is tonight at 10, back up your builds", 0),
                SavedMessage(SampleData.id(600), "601", null, "Mira Okafor", "Mira Okafor", "wifi password is on the fridge", 0),
            ), {}, {}
        )
    }
    @Test fun forward() = shot("forward") {
        ForwardContent(
            "Season 4 starts Friday!", "", {},
            listOf(ForwardTarget("600", "Zaffox", "Direct message", null), ForwardTarget("602", "Game night", "Group", null), ForwardTarget("802", "#general", "Blockhaven", null)),
            null, null, {}
        )
    }
    @Test fun poll() = shot("poll") { CreatePollContent(false, null, "Which biome should spawn in?", listOf("Cherry grove", "Mushroom island")) { _, _, _, _ -> } }
    @Test fun poll2() = shot("poll_2", swipes = 1) { CreatePollContent(false, null, "Which biome should spawn in?", listOf("Cherry grove", "Mushroom island")) { _, _, _, _ -> } }

    private val roll = ApplicationCommand(
        "1", "50", "1", "roll", "Roll some dice",
        listOf(
            CommandOption(4, "sides", "How many sides", true, emptyList()),
            CommandOption(3, "mode", "How to roll", false, listOf("Normal" to "n", "Advantage" to "a")),
            CommandOption(5, "secret", "Only you see it", false, emptyList()),
        ),
        "DiceBot", JSONObject()
    )
    @Test fun commandSuggestions() = shot("commands") {
        androidx.wear.compose.material3.ScreenScaffold {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(horizontal = 22.dp, vertical = 40.dp)) {
                CommandSuggestions(listOf(roll, roll.copy(id = "2", name = "poll", description = "Ask a question", appName = "PollBot")), "/") {}
            }
        }
    }
    @Test fun commandForm() = shot("command_form") { CommandFormScreen(roll, false, null) {} }

    private val select = (SampleData.botMessage.components[1] as MessageComponent.Row).children[0] as MessageComponent.Select
    @Test fun selectMenu() = shot("select_menu") { SelectMenuScreen(select, false) {} }

    @Test fun chatBot() = shot("chat_bot") {
        androidx.wear.compose.material3.ScreenScaffold(timeText = { TitledTimeText("#announcements") }) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(start = 20.dp, end = 18.dp, top = 34.dp)) {
                MessageBubble(
                    msg = SampleData.botMessage, isContinuation = false, imageLoader = LocalContext.current.imageLoader,
                    myId = SampleData.me.id, onReact = {}, onLongPress = {}, onComponent = {}
                )
            }
        }
    }
    @Test fun chatTranslated() = shot("chat_translated") {
        androidx.wear.compose.material3.ScreenScaffold(timeText = { TitledTimeText("Inês") }) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(start = 20.dp, end = 18.dp, top = 40.dp)) {
                MessageBubble(
                    msg = DiscordMessage(SampleData.id(5), "603", SampleData.ines, "vamos jogar hoje à noite? começa às 8", java.time.Instant.now().toString(), null),
                    isContinuation = false, imageLoader = LocalContext.current.imageLoader, myId = SampleData.me.id,
                    onReact = {}, onLongPress = {}, translation = "Translated from Portuguese\nshall we play tonight? it starts at 8"
                )
                MessageBubble(
                    msg = DiscordMessage(SampleData.id(4), "603", SampleData.spam, "buy cheap nitro", java.time.Instant.now().toString(), null),
                    isContinuation = false, imageLoader = LocalContext.current.imageLoader, myId = SampleData.me.id,
                    onReact = {}, onLongPress = {}, blocked = true
                )
            }
        }
    }
    @Test fun chatNewLine() = shot("chat_new_line") {
        ChatScreen(SampleData.general.id, SampleData.general.name, SampleData.blockhaven.id, currentUserId = SampleData.me.id,
            onOpenPins = {}, onOpenSearch = {}, onOpenMembers = {}, onOpenThreads = {})
    }
    @Test fun chatHeader() = shot("chat_header") {
        // Scrolled to the top: the shortcut buttons.
        ChatScreen("999", "general", SampleData.blockhaven.id, currentUserId = SampleData.me.id,
            onOpenPins = {}, onOpenSearch = {}, onOpenMembers = {}, onOpenThreads = {})
    }
    @Test fun chatQuickReplies() = shot("chat_quick_replies", swipes = 3) {
        ChatScreen(SampleData.dmZaffox.id, "Zaffox", null, currentUserId = SampleData.me.id, onCall = {}, onCreatePoll = {}, onOpenSearch = {}, onOpenPins = {})
    }
    @Test fun messageMenu() = shot("message_menu", swipes = 1) {
        MessageOptionsDialog(
            msg = SampleData.zaffoxMessages[4], isOwn = false,
            onPin = {}, onForward = {}, onSave = {}, onReadAloud = {}, onTranslate = {},
            onReply = {}, onCopy = {}, onEdit = null, onDelete = {}, onReact = {}, onDismiss = {}
        )
    }

    @Test fun members() = shot("members") {
        MembersContent(
            "#general", "12 online · 42 members",
            listOf(
                PeopleSection("Admins — 1", listOf(PersonRow(SampleData.teodor, "Teodor", OnlineStatus.ONLINE, null))),
                PeopleSection("Online — 11", listOf(
                    PersonRow(SampleData.zaffox, "Zaffox", OnlineStatus.ONLINE, "Playing Minecraft"),
                    PersonRow(SampleData.mira, "Mira Okafor", OnlineStatus.IDLE, "Listening to Spotify"),
                    PersonRow(SampleData.kai, "Kai Tanaka", OnlineStatus.DND, null),
                )),
            ), {}
        )
    }
    @Test fun friends() = shot("friends") {
        FriendsContent(
            incoming = listOf(SampleData.ana), outgoing = listOf(SampleData.ines),
            friends = listOf(PersonRow(SampleData.zaffox, "Zaffox", OnlineStatus.ONLINE, "Playing Minecraft"), PersonRow(SampleData.mira, "Mira Okafor", OnlineStatus.IDLE, null)),
            blocked = listOf(SampleData.spam), busy = null, error = null, onAccept = {}, onRemove = {}, onOpenDm = {}
        )
    }
    @Test fun friends2() = shot("friends_2", swipes = 2) {
        FriendsContent(
            incoming = listOf(SampleData.ana), outgoing = listOf(SampleData.ines),
            friends = listOf(PersonRow(SampleData.zaffox, "Zaffox", OnlineStatus.ONLINE, "Playing Minecraft"), PersonRow(SampleData.mira, "Mira Okafor", OnlineStatus.IDLE, null)),
            blocked = listOf(SampleData.spam), busy = null, error = null, onAccept = {}, onRemove = {}, onOpenDm = {}
        )
    }
    @Test fun newGroup() = shot("new_group") {
        NewDmContent(
            listOf(SampleData.zaffox, SampleData.mira, SampleData.kai), mapOf(SampleData.zaffox.id to OnlineStatus.ONLINE), "", {}, null, null, null, {}, {},
            groupMode = true, picked = setOf(SampleData.zaffox.id, SampleData.kai.id), onToggleGroup = {}
        )
    }
    @Test fun invite() = shot("invite") {
        InviteContent("", {}, false, InvitePreview("pixel", SampleData.pixelForge, "welcome", 1204, 87), false, null, false, false) {}
    }
    @Test fun joinServer() = shot("join_server") { InviteContent("", {}, true, null, false, null, false, false) {} }
    @Test fun nickname() = shot("nickname") { NicknameContent("Blockhaven", "Willow 🌙", "Willow", false, null, {}, {}) }
    @Test fun alerts() = shot("alerts") { AlertLevelContent("Blockhaven", FeaturePreferences.AlertLevel.MENTIONS) {} }
    @Test fun soundboard() = shot("soundboard") {
        SoundboardContent(listOf("🥁" to "Drum", "📯" to "Airhorn", "😂" to "Laugh", "🐐" to "Goat", "🔔" to "Bell", "👏" to "Clap").mapIndexed { i, (e, n) ->
            SoundboardSound("$i", n, e, 1.0, null, true)
        }, null) {}
    }
    @Test fun events() = shot("events") {
        ServerChannelsContent(
            "Blockhaven", emptyList(), false, null, {},
            events = listOf(
                ScheduledEvent("e1", "700", null, "Season 4 launch", null, System.currentTimeMillis() + 2 * 86_400_000L, 1, "Main stage", 38),
            ),
            interested = setOf("e1")
        )
    }
    @Test fun stage() = shot("stage") {
        VoiceChannelContent(
            "Town Hall", null, VoiceTone.QUIET,
            listOf(VoiceTile("1", "Teodor", null, true, false, false), VoiceTile("2", "Mira", null, false, false, false)),
            VoiceMode.Active(muted = true, deafened = false), System.currentTimeMillis() - 600_000, null,
            {}, {}, {}, {}, {},
            stage = StageState(listeners = 38, listening = true, requested = false)
        )
    }
    @Test fun voiceSoundboardButton() = shot("voice_soundboard", swipes = 1) {
        VoiceChannelContent(
            "Lobby", null, VoiceTone.QUIET,
            listOf(VoiceTile("1", "Mira", null, true, false, false), VoiceTile("2", "Kai", null, false, true, false)),
            VoiceMode.Active(muted = false, deafened = false), System.currentTimeMillis() - 252_000, null,
            {}, {}, {}, {}, {}, onOpenSoundboard = {}
        )
    }

    // The real screens with the new parts.
    @Test fun servers() = shot("servers_unread") { ServerScreen(onNavigateToChannels = { _, _ -> }, onJoinServer = {}) }
    @Test fun dms() = shot("dms_friends") { DmsScreen({ _, _ -> }) }
    @Test fun home() = shot("home_actions", swipes = 2) { HomeScreen({}, {}, {}, {}, { _, _, _, _ -> }) }
    @Test fun profile() = shot("profile_actions", swipes = 1) { UserProfileScreen(SampleData.zaffox.id, initialUser = SampleData.zaffox, onBack = {}) }
    @Test fun settingsExtras() = shot("settings_extras", swipes = 5) { SettingsScreen() }
    @Test fun photos() = shot("photos") {
        PhotoGridContent(
            (1..9).map { MediaImage(it.toLong(), android.net.Uri.parse("content://x/$it"), "p$it", "image/jpeg", 0) },
            LocalContext.current.imageLoader, {}, {}
        )
    }
}

