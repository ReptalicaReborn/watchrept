package com.dihcord.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.dihcord.app.SetupPreferences
import com.dihcord.app.api.DiscordRestClient
import com.dihcord.app.captcha.WifiRequest
import com.dihcord.app.captcha.qrBitmap
import com.dihcord.app.captcha.wlanAddresses
import com.dihcord.app.discordApp
import com.dihcord.app.login.LoginWebServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LOGIN_PORT = 8082

/**
 * Email/password sign-in: the watch serves a login page, the user fills it in (including 2FA and any captcha) on
 * their phone, and the watch talks to Discord and keeps the resulting token.
 */
@Composable
fun EmailLoginScreen(onSetupComplete: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()

    var addresses by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("Open the page on your phone") }
    var error by remember { mutableStateOf("") }
    var finishing by remember { mutableStateOf(false) }
    // Bumping this replaces the server (new code, fresh login) after a failure.
    var generation by remember { mutableIntStateOf(0) }

    BackHandler { onBack() }

    val server = remember(generation) {
        LoginWebServer(
            port = LOGIN_PORT,
            onStatus = { text -> scope.launch { status = text } },
        ) { token ->
            scope.launch {
                finishing = true
                status = "Checking account…"
                val check = DiscordRestClient(token).getCurrentUser()
                if (check.isSuccess) {
                    SetupPreferences.saveToken(context, token)
                    context.discordApp.initRepository(token)
                    status = "Signed in!"
                    delay(400)
                    onSetupComplete()
                } else {
                    finishing = false
                    error = "Discord gave a token that doesn't work. Try again."
                    generation++
                }
            }
        }
    }

    DisposableEffect(server) {
        val wifi = WifiRequest(context)
        wifi.acquire()
        server.start { e ->
            scope.launch { error = "Couldn't start the sign-in page. Port $LOGIN_PORT may be in use." }
        }
        onDispose {
            server.stop()
            wifi.release()
        }
    }

    // Wi-Fi may take a moment to come up after it is requested; keep refreshing the address list.
    LaunchedEffect(server) {
        while (true) {
            addresses = wlanAddresses()
            delay(2_000)
        }
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            item { Text("Email & password", style = MaterialTheme.typography.titleMedium) }
            item {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (!finishing) {
                if (addresses.isEmpty()) {
                    item {
                        Text(
                            "Waiting for Wi-Fi… make sure the watch is connected to Wi-Fi.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    val port = server.boundPort.takeIf { it > 0 } ?: LOGIN_PORT
                    val firstUrl = "http://${addresses.first()}:$port/${server.code}"
                    item {
                        val qr = remember(firstUrl) { qrBitmap(firstUrl, 240) }
                        if (qr != null) {
                            Image(
                                bitmap = qr.asImageBitmap(),
                                contentDescription = "QR code for the sign-in page",
                                modifier = Modifier
                                    .size(96.dp)
                                    .background(Color.White)
                            )
                        }
                    }
                    addresses.forEach { addr ->
                        item {
                            Text(
                                "$addr:$port/${server.code}",
                                style = MaterialTheme.typography.titleSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    item {
                        Text(
                            "Scan with your phone on the same Wi-Fi. 2FA codes and captchas are entered there too.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            if (error.isNotEmpty()) {
                item {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (!finishing) {
                item {
                    Button(
                        onClick = {
                            error = ""
                            status = "Open the page on your phone"
                            generation++
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) { Text("New code") }
                }
                item {
                    Button(
                        onClick = onBack,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) { Text("Back") }
                }
            }
        }
    }
}
