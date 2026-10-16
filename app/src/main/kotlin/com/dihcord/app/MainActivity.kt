package com.dihcord.app

import android.content.Intent
import androidx.navigation.NavType
import androidx.navigation.navArgument
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.dihcord.app.screens.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    var activeChannelId: String? = null

    /** A conversation to open, set by tapping a notification. */
    private data class ChatTarget(val channelId: String, val name: String, val guildId: String?)

    private var pendingChat by mutableStateOf<ChatTarget?>(null)

    private fun readChatTarget(intent: Intent?) {
        val channelId = intent?.getStringExtra(Notifier.EXTRA_CHANNEL_ID) ?: return
        // Route segments can't contain these characters.
        val name = (intent.getStringExtra(Notifier.EXTRA_CHANNEL_NAME) ?: channelId)
            .replace(Regex("[/?#%]"), " ").ifBlank { channelId }
        pendingChat = ChatTarget(channelId, name, intent.getStringExtra(Notifier.EXTRA_GUILD_ID))
        intent.removeExtra(Notifier.EXTRA_CHANNEL_ID)
        // The notification was tapped: drop its message history so the next one starts fresh.
        Notifier.clear(this, channelId)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readChatTarget(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val token = SetupPreferences.getToken(this)
        // The process (and its gateway connection) may outlive the activity, e.g. while the notification
        // service runs; only build a repository when there isn't one yet.
        if (token != null && discordApp.repository == null) {
            discordApp.initRepository(token)
        }

        readChatTarget(intent)
        if (token != null && SetupPreferences.getNotificationsEnabled(this)) {
            NotificationService.start(this)
        }

        setContent {
            DihcordTheme {

                // Observed as a flow so a repository created later (e.g. by logging in) is picked up too.
                val repo by discordApp.repositoryFlow.collectAsState()
                val tokenInvalidFlow: StateFlow<Boolean> = remember(repo) {
                    repo?.isTokenInvalid ?: MutableStateFlow(false)
                }
                val isTokenInvalid by tokenInvalidFlow.collectAsState()
                var showInvalidTokenPopup by remember { mutableStateOf(false) }

                androidx.compose.runtime.LaunchedEffect(isTokenInvalid) {
                    if (isTokenInvalid) {
                        SetupPreferences.clearToken(this@MainActivity)
                        discordApp.clearRepository()
                        showInvalidTokenPopup = true
                    }
                }

                AppScaffold(timeText = { TimeText() }) {
                    val navController = rememberSwipeDismissableNavController()

                    val target = pendingChat
                    androidx.compose.runtime.LaunchedEffect(target) {
                        if (target != null && SetupPreferences.getToken(this@MainActivity) != null) {
                            navController.navigate(
                                "chatscreen/${target.channelId}/${Uri.encode(target.name)}/${target.guildId ?: "dm"}"
                            ) { launchSingleTop = true }
                        }
                        pendingChat = null
                    }
                    SwipeDismissableNavHost(
                        navController = navController,
                        startDestination = if (token != null) "home" else "Welcome"
                    ) {

                        composable("home") {
                            HomeScreen(
                                onNavigateToDms = { navController.navigate("DMs") },
                                onNavigateToServers = { navController.navigate("servers") },
                                onNavigateToWelcome = { navController.navigate("Welcome") },
                                onNavigateToSettings = { navController.navigate("settings") },
                                onNavigateToChat = { chId, chName, guildId, jumpTo ->
                                    val guildSeg = guildId ?: "dm"
                                    val jump = jumpTo?.let { "?jump=${Uri.encode(it)}" }.orEmpty()
                                    navController.navigate("chatscreen/$chId/${Uri.encode(chName)}/$guildSeg$jump")
                                }
                            )
                        }

                        composable("Welcome") {
                            WelcomeScreen(onSetupComplete = {
                                navController.navigate("home") {
                                    popUpTo("Welcome") { inclusive = true }
                                }
                            }, onNavigateToQrLogin = {
                                navController.navigate("qrlogin")
                            }, onNavigateToEmailLogin = {
                                navController.navigate("emaillogin")
                            })
                        }

                        composable("emaillogin") {
                            EmailLoginScreen(
                                onSetupComplete = {
                                    navController.navigate("home") {
                                        popUpTo(0) { inclusive = true }
                                    }
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable("qrlogin") {
                            QrLoginScreen(
                                onSetupComplete = {
                                    navController.navigate("home") {
                                        popUpTo(0) { inclusive = true }
                                    }
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable("settings") {
                            SettingsScreen(
                                onLogOut = {
                                    navController.navigate("Welcome") {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(
                            "chatscreen/{channelId}/{channelName}/{guildId}?jump={jump}",
                            arguments = listOf(navArgument("jump") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            })
                        ) { back ->
                            val channelId =
                                back.arguments?.getString("channelId") ?: return@composable
                            val channelName = back.arguments?.getString("channelName") ?: channelId
                            val guildIdArg = back.arguments?.getString("guildId")
                            val guildId = if (guildIdArg == "dm") null else guildIdArg
                            activeChannelId = channelId

                            // A new conversation (e.g. opened from a notification while another chat is
                            // on screen, which reuses the destination) must not inherit draft/reply state.
                            key(channelId) {
                                ChatScreen(
                                    channelId = channelId,
                                    channelName = channelName,
                                    guildId = guildId,
                                    jumpTo = back.arguments?.getString("jump"),
                                    onNavigateToProfile = { userId, user ->
                                        val encodedName = java.net.URLEncoder.encode(
                                            user?.displayName ?: userId,
                                            "UTF-8"
                                        )
                                        navController.navigate("userprofile/$userId/$encodedName/${guildIdArg ?: "dm"}")
                                    },
                                    onNavigateToThread = { threadId, threadName ->
                                        val encodedName =
                                            java.net.URLEncoder.encode(threadName, "UTF-8")
                                        navController.navigate("thread/$threadId/$encodedName/${guildIdArg ?: "dm"}")
                                    },
                                    onOpenThreads = {
                                        val encoded = java.net.URLEncoder.encode(channelName, "UTF-8")
                                        navController.navigate("threads/$channelId/$encoded/${guildIdArg ?: "dm"}/channel")
                                    },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }

                        composable("thread/{threadId}/{threadName}/{guildId}") { back ->
                            val threadId =
                                back.arguments?.getString("threadId") ?: return@composable
                            val threadName = back.arguments?.getString("threadName")
                                ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: threadId
                            val guildIdArg = back.arguments?.getString("guildId")
                            val guildId = if (guildIdArg == "dm") null else guildIdArg
                            activeChannelId = threadId
                            ThreadScreen(
                                threadId = threadId,
                                threadName = threadName,
                                guildId = guildId,
                                onNavigateToProfile = { userId, user ->
                                    val encodedName = java.net.URLEncoder.encode(
                                        user?.displayName ?: userId,
                                        "UTF-8"
                                    )
                                    navController.navigate("userprofile/$userId/$encodedName/${guildIdArg ?: "dm"}")
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable("userprofile/{userId}/{displayName}/{guildId}") { back ->
                            val userId = back.arguments?.getString("userId") ?: return@composable
                            val guildIdArg = back.arguments?.getString("guildId")
                            val guildId = if (guildIdArg == "dm") null else guildIdArg
                            UserProfileScreen(
                                userId = userId,
                                guildId = guildId,
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable("ServerChannels/{guildId}/{guildName}") { back ->
                            val guildId = back.arguments?.getString("guildId") ?: return@composable
                            val guildName = back.arguments?.getString("guildName") ?: guildId
                            activeChannelId = null
                            ServerChannels(
                                guildId = guildId,
                                guildName = guildName,
                                onNavigateToChatScreen = { chId, chName ->
                                    navController.navigate("chatscreen/$chId/${Uri.encode(chName)}/$guildId")
                                },
                                onOpenForum = { chId, chName ->
                                    val encoded = java.net.URLEncoder.encode(chName, "UTF-8")
                                    navController.navigate("threads/$chId/$encoded/$guildId/forum")
                                }
                            )
                        }

                        // Threads of a text channel, or posts of a forum channel ("forum"/"channel").
                        composable("threads/{channelId}/{channelName}/{guildId}/{kind}") { back ->
                            val chId = back.arguments?.getString("channelId") ?: return@composable
                            val chName = back.arguments?.getString("channelName")
                                ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: chId
                            val gIdArg = back.arguments?.getString("guildId")
                            activeChannelId = null
                            ThreadListScreen(
                                channelId = chId,
                                channelName = chName,
                                guildId = gIdArg?.takeIf { it != "dm" },
                                isForum = back.arguments?.getString("kind") == "forum",
                                onOpenThread = { threadId, threadName ->
                                    val encodedName = java.net.URLEncoder.encode(threadName, "UTF-8")
                                    navController.navigate("thread/$threadId/$encodedName/${gIdArg ?: "dm"}")
                                }
                            )
                        }

                        composable("DMs") {
                            activeChannelId = null
                            DmsScreen(onNavigateToChatScreen = { chId, chName ->
                                navController.navigate("chatscreen/$chId/${Uri.encode(chName)}/dm")
                            })
                        }

                        composable("servers") {
                            activeChannelId = null
                            ServerScreen(
                                onNavigateToChannels = { gId, gName ->
                                    navController.navigate("ServerChannels/$gId/${Uri.encode(gName)}")
                                },
                                onOpenFolder = { folder -> navController.navigate("servers/folder/${Uri.encode(folder)}") },
                                onNavigateToChat = { chId, chName, gId, jumpTo ->
                                    navController.navigate(
                                        "chatscreen/$chId/${Uri.encode(chName)}/$gId?jump=${Uri.encode(jumpTo)}"
                                    )
                                }
                            )
                        }

                        composable("servers/folder/{folderKey}") { back ->
                            activeChannelId = null
                            ServerScreen(
                                folderKey = back.arguments?.getString("folderKey"),
                                onNavigateToChannels = { gId, gName ->
                                    navController.navigate("ServerChannels/$gId/${Uri.encode(gName)}")
                                }
                            )
                        }
                    }

                    val captcha by com.dihcord.app.captcha.CaptchaBroker.pending.collectAsState()
                    captcha?.let { com.dihcord.app.captcha.CaptchaScreen(it) }

                    if (showInvalidTokenPopup) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center
                        ) {
                            ScreenScaffold(scrollState = rememberScalingLazyListState()) {
                                ScalingLazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    item {
                                        Text(
                                            "Invalid Token",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.error,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp)
                                        )
                                    }
                                    item {
                                        Spacer(Modifier.height(4.dp))
                                    }
                                    item {
                                        Text(
                                            "Token is invalid or has expired. \n You have been logged out.",
                                            style = MaterialTheme.typography.bodySmall,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp)
                                        )
                                    }
                                    item {
                                        Spacer(Modifier.height(8.dp))
                                    }
                                    item {
                                        Button(
                                            onClick = {
                                                showInvalidTokenPopup = false
                                                navController.navigate("Welcome") {
                                                    popUpTo(0) { inclusive = true }
                                                }
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth(0.85f)
                                                .height(36.dp),
                                            colors = ButtonDefaults.filledTonalButtonColors()
                                        ) {
                                            Text("OK")
                                        }
                                    }
                                }
                            }
                        }
                    }

                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppState.onStart(discordApp)
    }

    override fun onStop() {
        AppState.onStop(discordApp)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        discordApp.repository?.refreshOnResume(activeChannelId)
    }
}
