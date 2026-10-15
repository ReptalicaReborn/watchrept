package com.watchrept.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.watchrept.app.DiscordViewModel

private const val LOGIN = "login"
private const val GUILDS = "guilds"
private const val CHANNELS = "channels"
private const val CHAT = "chat"

@Composable
fun DiscordApp(vm: DiscordViewModel) {
    MaterialTheme {
        val state by vm.state.collectAsState()
        val nav = rememberSwipeDismissableNavController()

        LaunchedEffect(state.loggedIn) {
            if (state.loggedIn) {
                nav.navigate(GUILDS) { popUpTo(LOGIN) { inclusive = true } }
            } else if (nav.currentBackStackEntry?.destination?.route != LOGIN) {
                nav.navigate(LOGIN) { popUpTo(GUILDS) { inclusive = true } }
            }
        }

        Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
            SwipeDismissableNavHost(navController = nav, startDestination = LOGIN) {
                composable(LOGIN) { LoginScreen(error = state.error, onLogin = vm::login) }
                composable(GUILDS) {
                    ListScreen(
                        title = "Servers",
                        items = state.guilds.map { it.name },
                        status = state.error ?: if (state.loading) "Loading…" else null,
                        onClick = { i ->
                            vm.openGuild(state.guilds[i].id)
                            nav.navigate(CHANNELS)
                        },
                        footer = "Log out" to vm::logout,
                    )
                }
                composable(CHANNELS) {
                    ListScreen(
                        title = "Channels",
                        items = state.channels.map { "# ${it.name}" },
                        status = state.error ?: if (state.loading) "Loading…" else null,
                        onClick = { i ->
                            vm.openChannel(state.channels[i].id)
                            nav.navigate(CHAT)
                        },
                    )
                }
                composable(CHAT) {
                    ChatScreen(state = state, onSend = vm::send, onExit = vm::closeChannel)
                }
            }
            TimeText()
        }
    }
}

@Composable
private fun LoginScreen(error: String?, onLogin: (String, Boolean) -> Unit) {
    var token by remember { mutableStateOf("") }
    var isBot by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Token", style = MaterialTheme.typography.title3)
        TextInput(
            value = token,
            onValueChange = { token = it },
            hint = "Paste token",
            transformation = PasswordVisualTransformation(),
            onDone = { onLogin(token, isBot) },
        )
        error?.let { Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption2) }
        Chip(
            label = { Text(if (isBot) "Bot account" else "User account") },
            colors = ChipDefaults.secondaryChipColors(),
            onClick = { isBot = !isBot },
        )
        Chip(label = { Text("Connect") }, onClick = { onLogin(token, isBot) })
    }
}

@Composable
private fun ListScreen(
    title: String,
    items: List<String>,
    status: String?,
    onClick: (Int) -> Unit,
    footer: Pair<String, () -> Unit>? = null,
) {
    ScalingLazyColumn(Modifier.fillMaxSize(), state = rememberScalingLazyListState()) {
        item { Text(title, style = MaterialTheme.typography.title3) }
        status?.let { item { Text(it, style = MaterialTheme.typography.caption2) } }
        items(items.size) { i ->
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text(items[i], maxLines = 2) },
                colors = ChipDefaults.secondaryChipColors(),
                onClick = { onClick(i) },
            )
        }
        footer?.let { (label, action) ->
            item { Chip(label = { Text(label) }, colors = ChipDefaults.secondaryChipColors(), onClick = action) }
        }
    }
}

@Composable
private fun ChatScreen(state: com.watchrept.app.UiState, onSend: (String) -> Unit, onExit: () -> Unit) {
    val listState = rememberScalingLazyListState()
    var draft by remember { mutableStateOf("") }

    androidx.compose.runtime.DisposableEffect(Unit) { onDispose(onExit) }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.size)
    }

    ScalingLazyColumn(Modifier.fillMaxSize(), state = listState) {
        item { Text("Chat", style = MaterialTheme.typography.title3) }
        state.error?.let { item { Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption2) } }
        items(state.messages, key = { it.id }) { m ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(m.author.displayName, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.primary)
                Text(m.content.ifEmpty { "[attachment]" }, style = MaterialTheme.typography.body2)
            }
        }
        item {
            TextInput(
                value = draft,
                onValueChange = { draft = it },
                hint = "Message",
                onDone = { onSend(draft); draft = "" },
            )
        }
    }
}

@Composable
private fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    onDone: () -> Unit,
    transformation: VisualTransformation = VisualTransformation.None,
) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .background(MaterialTheme.colors.surface, MaterialTheme.shapes.large)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurfaceVariant)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = transformation,
            textStyle = MaterialTheme.typography.body2.copy(color = MaterialTheme.colors.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onDone() }, onDone = { onDone() }),
        )
    }
}
