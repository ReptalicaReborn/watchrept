package com.dihcord.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import coil.imageLoader
import com.dihcord.app.DihcordApp
import com.dihcord.app.api.Reaction
import com.dihcord.app.api.ReactionEmoji
import com.dihcord.app.screens.ChatScreen
import com.dihcord.app.screens.DmsScreen
import com.dihcord.app.screens.EmailLoginScreen
import com.dihcord.app.screens.EmojiStickerScreen
import com.dihcord.app.screens.GifPickerScreen
import com.dihcord.app.screens.HomeScreen
import com.dihcord.app.screens.MessageOptionsDialog
import com.dihcord.app.screens.NewDmScreen
import com.dihcord.app.screens.QrLoginScreen
import com.dihcord.app.screens.ReactorsScreen
import com.dihcord.app.screens.ServerChannels
import com.dihcord.app.screens.ServerScreen
import com.dihcord.app.screens.SettingsScreen
import com.dihcord.app.screens.ThreadListScreen
import com.dihcord.app.screens.UserProfileScreen
import com.dihcord.app.screens.VoiceChannelScreen
import com.dihcord.app.screens.WelcomeContent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The real screens, fed by a [SampleData] repository that never connects (its REST calls go to a closed
 * local port and fail at once). PNGs land in app/build/ui-shots with a "full_" prefix.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w216dp-h216dp-round-watch-xhdpi")
class FullScreenShotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app get() = RuntimeEnvironment.getApplication() as DihcordApp

    @Before
    fun seed() = app.useRepositoryForTests(SampleData.repository())

    @After
    fun clear() = app.useRepositoryForTests(null)

    private fun shot(
        name: String,
        swipes: Int = 0,
        act: androidx.compose.ui.test.junit4.AndroidComposeTestRule<androidx.test.ext.junit.rules.ActivityScenarioRule<ComponentActivity>, ComponentActivity>.() -> Unit = {},
        content: @androidx.compose.runtime.Composable () -> Unit
    ) = rule.watchShot("full_$name", settleMs = 600, swipes = swipes, act = act, content = content)

    @Test fun settingsThemes() = shot("settings_themes", act = {
        repeat(2) {
            onRoot().performTouchInput { swipeUp(startY = height * 0.85f, endY = height * 0.15f, durationMillis = 300) }
            mainClock.advanceTimeBy(1_000)
        }
        onNode(androidx.compose.ui.test.hasText("Theme: ", substring = true)).performClick()
        mainClock.advanceTimeBy(500)
        onRoot().performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.3f, durationMillis = 300) }
    }) { SettingsScreen() }
    @Test fun settingsStatus() = shot("settings_status", act = {
        onNode(androidx.compose.ui.test.hasText("Online")).performClick()
    }) { SettingsScreen() }

    @Test fun home() = shot("home") { HomeScreen({}, {}, {}, {}, { _, _, _, _ -> }) }
    @Test fun dms() = shot("dms") { DmsScreen({ _, _ -> }) }
    @Test fun servers() = shot("servers") { ServerScreen(onNavigateToChannels = { _, _ -> }) }
    @Test fun channels() = shot("channels") { ServerChannels(SampleData.blockhaven.id, SampleData.blockhaven.name, { _, _ -> }) }
    @Test fun chatServer() = shot("chat_server") {
        ChatScreen(SampleData.general.id, SampleData.general.name, SampleData.blockhaven.id, currentUserId = SampleData.me.id)
    }
    @Test fun chatDm() = shot("chat_dm") {
        ChatScreen(SampleData.dmZaffox.id, "Zaffox", null, currentUserId = SampleData.me.id, onCall = {})
    }
    @Test fun chatRich() = shot("chat_rich") {
        ChatScreen(SampleData.announcements.id, SampleData.announcements.name, SampleData.blockhaven.id, currentUserId = SampleData.me.id)
    }
    @Test fun chatRich2() = shot("chat_rich_2", swipes = 1) {
        ChatScreen(SampleData.announcements.id, SampleData.announcements.name, SampleData.blockhaven.id, currentUserId = SampleData.me.id)
    }
    @Test fun serverMenuReal() = shot("servers_folder") { ServerScreen(folderKey = "missing", onNavigateToChannels = { _, _ -> }) }
    @Test fun messageMenu() = shot("message_menu") {
        MessageOptionsDialog(
            msg = SampleData.zaffoxMessages[3], isOwn = true,
            onReply = {}, onCopy = {}, onEdit = {}, onDelete = {}, onReact = {}, onDismiss = {}
        )
    }
    @Test fun deleteConfirm() = shot("delete_confirm") {
        MessageOptionsDialog(
            msg = SampleData.zaffoxMessages[3], isOwn = true,
            onReply = {}, onCopy = {}, onEdit = {}, onDelete = {}, onReact = {}, onDismiss = {},
            confirmingDelete = true
        )
    }
    @Test fun settings() = shot("settings") { SettingsScreen() }
    @Test fun settings2() = shot("settings_2", swipes = 1) { SettingsScreen() }
    @Test fun settings3() = shot("settings_3", swipes = 2) { SettingsScreen() }
    @Test fun settings4() = shot("settings_4", swipes = 3) { SettingsScreen() }
    @Test fun settings5() = shot("settings_5", swipes = 5) { SettingsScreen() }
    @Test fun settings6() = shot("settings_6", swipes = 8) { SettingsScreen() }
    @Test fun chatDmBottom() = shot("chat_dm_bottom", swipes = 3) {
        ChatScreen(SampleData.dmZaffox.id, "Zaffox", null, currentUserId = SampleData.me.id, onCall = {})
    }
    @Test fun thread() = shot("thread") {
        com.dihcord.app.screens.ThreadScreen(SampleData.general.id, "Castle build", SampleData.blockhaven.id)
    }
    @Test fun profile2() = shot("profile_2", swipes = 1) { UserProfileScreen(SampleData.zaffox.id, initialUser = SampleData.zaffox, onBack = {}) }
    @Test fun channels2() = shot("channels_2", swipes = 1) { ServerChannels(SampleData.blockhaven.id, SampleData.blockhaven.name, { _, _ -> }) }
    @Test fun profile() = shot("profile") { UserProfileScreen(SampleData.zaffox.id, initialUser = SampleData.zaffox, onBack = {}) }
    @Test fun threads() = shot("threads") {
        ThreadListScreen(SampleData.ideas.id, SampleData.ideas.name, SampleData.blockhaven.id, isForum = true) { _, _ -> }
    }
    @Test fun newDm() = shot("new_dm") { NewDmScreen { _, _ -> } }
    @Test fun emoji() = shot("emoji") { EmojiStickerScreen(0, SampleData.blockhaven.id, onEmojiPicked = { _, _ -> }, onUnicodeEmojiPicked = {}, onStickerPicked = { _, _ -> }) }
    @Test fun gifs() = shot("gifs") { GifPickerScreen {} }
    @Test fun reactors() = shot("reactors") {
        ReactorsScreen(
            SampleData.general.id, SampleData.generalMessages[1].id,
            Reaction(ReactionEmoji(null, "🔥", false), 3, me = false),
            LocalContext.current.imageLoader, {}
        )
    }
    @Test fun voice() = shot("voice") { VoiceChannelScreen(SampleData.blockhaven.id, SampleData.lobby.id, SampleData.lobby.name) }
    // The real Welcome screen holds a Wi-Fi lock and a web server, which Robolectric can't fake; draw its content.
    private fun welcome(name: String, running: Boolean, expanded: Boolean, swipes: Int = 0) = shot(name, swipes) {
        WelcomeContent(
            serverRunning = running,
            serverStatus = if (running) "Open this on a phone or computer:" else "",
            pin = if (running) "4821" else null,
            addresses = if (running) listOf("192.168.1.42") else emptyList(),
            error = null,
            onEmail = {}, onQr = {}, onTypeToken = {}, onPasteToken = {}, onStartBrowser = {}, onStopBrowser = {},
            startExpanded = expanded
        )
    }
    @Test fun welcome() = welcome("welcome", running = false, expanded = false)
    @Test fun welcomeMore() = welcome("welcome_more", running = false, expanded = true, swipes = 1)
    @Test fun welcomeBrowser() = welcome("welcome_browser", running = true, expanded = true, swipes = 2)
    @Test fun qrLogin() = shot("qr_login") { QrLoginScreen({}, {}) }
    @Test fun emailLogin() = shot("email_login") { EmailLoginScreen({}, {}) }
}
