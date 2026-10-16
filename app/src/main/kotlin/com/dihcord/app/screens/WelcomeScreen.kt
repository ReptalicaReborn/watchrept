package com.dihcord.app.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.dihcord.app.R
import android.app.RemoteInput
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender
import com.dihcord.app.SetupPreferences
import com.dihcord.app.TokenWebServer
import com.dihcord.app.api.DiscordRestClient
import com.dihcord.app.discordApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val INPUT_KEY = "token_input"

@Composable
fun WelcomeScreen(
    onSetupComplete: () -> Unit,
    onNavigateToQrLogin: () -> Unit,
    onNavigateToEmailLogin: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var error by remember { mutableStateOf("") }
    var serverStatus by remember { mutableStateOf("") }
    var serverAddresses by remember { mutableStateOf<List<String>>(emptyList()) }
    var webServer by remember { mutableStateOf<TokenWebServer?>(null) }

    val wifiManager = remember {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    val wifiLock = remember {
        wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Dihcord:TokenServer")
    }
    val connectivityManager = remember {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    var wifiNetworkCallback by remember { mutableStateOf<ConnectivityManager.NetworkCallback?>(null) }

    fun acquireWifiLock() {
        if (!wifiLock.isHeld) wifiLock.acquire()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivityManager.bindProcessToNetwork(network)
            }

            override fun onLost(network: Network) {
                connectivityManager.bindProcessToNetwork(null)
            }
        }
        connectivityManager.requestNetwork(req, cb)
        wifiNetworkCallback = cb
    }

    fun releaseWifiLock() {
        if (wifiLock.isHeld) wifiLock.release()
        wifiNetworkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        wifiNetworkCallback = null
        connectivityManager.bindProcessToNetwork(null)
    }

    DisposableEffect(Unit) {
        onDispose {
            webServer?.stop()
            releaseWifiLock()
        }
    }

    val inputLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val bundle: Bundle? = RemoteInput.getResultsFromIntent(
            result.data ?: return@rememberLauncherForActivityResult
        )
        val token = bundle?.getCharSequence(INPUT_KEY)?.toString()?.trim()
        if (!token.isNullOrBlank()) {
            scope.launch {
                error = ""
                serverStatus = "Validating token…"
                val check = DiscordRestClient(token).getCurrentUser()
                if (check.isSuccess) {
                    SetupPreferences.saveToken(context, token)
                    context.discordApp.initRepository(token)
                    onSetupComplete()
                } else {
                    error = "That token didn't work."
                    serverStatus = ""
                }
            }
        }
    }

    fun openTokenInput() {
        val ri = RemoteInput.Builder(INPUT_KEY)
            .setLabel("Paste Discord token")
            .wearableExtender {
                setEmojisAllowed(false)
                setInputActionType(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
            }.build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(ri))
        inputLauncher.launch(intent)
    }

    fun startWebServer() {
        acquireWifiLock()
        lateinit var srv: TokenWebServer
        srv = TokenWebServer(
            port = 8080,
            onStartFailed = { e ->
                scope.launch {
                    if (webServer !== srv) return@launch
                    error = "Couldn't start the browser sign-in. Port 8080 may be in use."
                    srv.stop()
                    webServer = null
                    serverStatus = ""
                    serverAddresses = emptyList()
                    releaseWifiLock()
                }
            }
        ) { token ->
            scope.launch {
                serverStatus = "Validating token…"
                val check = DiscordRestClient(token).getCurrentUser()
                if (check.isSuccess) {
                    SetupPreferences.saveToken(context, token)
                    context.discordApp.initRepository(token)
                    serverStatus = "Token verified!"
                    delay(500)
                    webServer?.stop()
                    webServer = null
                    releaseWifiLock()
                    onSetupComplete()
                } else {
                    error = "The browser sent a token that didn't work."
                    serverStatus = "Open this on a phone or computer:"
                    webServer?.rearm()
                }
            }
        }
        error = ""
        webServer = srv
        srv.start()
        serverAddresses = srv.getLocalAddresses()
        serverStatus = if (serverAddresses.isEmpty()) {
            "Connect the watch to Wi-Fi first."
        } else {
            "Open this on a phone or computer:"
        }
    }

    fun pasteToken() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clipData = clipboard.primaryClip
        val pastedText = if (clipData != null && clipData.itemCount > 0) {
            clipData.getItemAt(0).text?.toString()?.trim()
        } else null
        if (pastedText.isNullOrBlank()) {
            error = "The clipboard is empty."
            return
        }
        scope.launch {
            error = ""
            serverStatus = "Validating token…"
            val check = DiscordRestClient(pastedText).getCurrentUser()
            if (check.isSuccess) {
                SetupPreferences.saveToken(context, pastedText)
                context.discordApp.initRepository(pastedText)
                onSetupComplete()
            } else {
                error = "That token didn't work."
                serverStatus = ""
            }
        }
    }

    WelcomeContent(
        serverRunning = webServer != null,
        serverStatus = serverStatus,
        pin = webServer?.pin,
        addresses = serverAddresses,
        error = error.takeIf { it.isNotEmpty() },
        onEmail = onNavigateToEmailLogin,
        onQr = onNavigateToQrLogin,
        onTypeToken = { openTokenInput() },
        onPasteToken = { pasteToken() },
        onStartBrowser = { startWebServer() },
        onStopBrowser = {
            webServer?.stop()
            webServer = null
            serverStatus = ""
            releaseWifiLock()
        }
    )
}

/**
 * The sign-in screen from plain data: the logo, the two usual ways in (email, QR) with a line each,
 * and the token ways folded under "More ways to sign in" so they don't crowd a first-time user.
 */
@Composable
internal fun WelcomeContent(
    serverRunning: Boolean,
    serverStatus: String,
    pin: String?,
    addresses: List<String>,
    error: String?,
    onEmail: () -> Unit,
    onQr: () -> Unit,
    onTypeToken: () -> Unit,
    onPasteToken: () -> Unit,
    onStartBrowser: () -> Unit,
    onStopBrowser: () -> Unit,
    startExpanded: Boolean = false,
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    var more by remember { mutableStateOf(startExpanded || serverRunning) }
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painterResource(R.mipmap.ic_launcher_round), null,
                        Modifier.size(40.dp).clip(CircleShape)
                    )
                    ScreenTitle("Dihcord", Modifier.padding(top = 4.dp))
                    Text(
                        "Discord on your wrist. Sign in to start.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(0.8f).padding(bottom = 4.dp)
                    )
                }
            }
            item {
                Button(
                    onClick = onEmail,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    icon = { Icon(painterResource(R.drawable.mail), null, Modifier.size(20.dp)) },
                    label = { Text("Email & password") },
                    secondaryLabel = { Text("Type it on your phone") }
                )
            }
            item {
                Button(
                    onClick = onQr,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { Icon(painterResource(R.drawable.qr_code), null, Modifier.size(20.dp)) },
                    label = { Text("Scan a QR code") },
                    secondaryLabel = { Text("With the Discord app") }
                )
            }
            item {
                CompactButton(
                    onClick = { more = !more },
                    label = { Text(if (more) "Fewer options" else "More ways to sign in") },
                    icon = {
                        Icon(
                            painterResource(R.drawable.chevron), null,
                            Modifier.size(16.dp).graphicsLayer { rotationZ = if (more) 180f else 0f }
                        )
                    },
                    colors = ButtonDefaults.childButtonColors(),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (more) {
                item {
                    Button(
                        onClick = onTypeToken,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        icon = { Icon(painterResource(R.drawable.key), null, Modifier.size(20.dp)) },
                        label = { Text("Type a token") }
                    )
                }
                item {
                    Button(
                        onClick = onPasteToken,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        icon = { Icon(painterResource(R.drawable.paste), null, Modifier.size(20.dp)) },
                        label = { Text("Paste a token") }
                    )
                }
                if (!serverRunning) {
                    item {
                        Button(
                            onClick = onStartBrowser,
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec),
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            icon = { Icon(painterResource(R.drawable.globe), null, Modifier.size(20.dp)) },
                            label = { Text("Send from a browser") },
                            secondaryLabel = { Text("On the same Wi-Fi") }
                        )
                    }
                } else {
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                serverStatus,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center
                            )
                            if (pin != null) {
                                Text("PIN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                                Text(pin, style = MaterialTheme.typography.titleLarge.merge(TabularNumbers))
                            }
                            addresses.forEach { addr ->
                                Text(
                                    "http://$addr:8080",
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                    item {
                        CompactButton(
                            onClick = onStopBrowser,
                            label = { Text("Stop") },
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            if (error != null) {
                item {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(0.85f).padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
