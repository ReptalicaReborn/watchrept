package com.dihcord.app.captcha

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Full-screen prompt shown while Discord wants a captcha. Starts [CaptchaWebServer] and tells the user which
 * address to open on their phone; tapping Cancel gives up (the action then fails with a clear error).
 */
@Composable
fun CaptchaScreen(pending: CaptchaBroker.Pending) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    var addresses by remember { mutableStateOf<List<String>>(emptyList()) }
    var received by remember { mutableStateOf(false) }

    val server = remember(pending) {
        CaptchaWebServer(pending.challenge) { token ->
            received = true
            CaptchaBroker.complete(token)
        }
    }

    DisposableEffect(server) {
        val wifi = WifiRequest(context)
        wifi.acquire()
        server.start()
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
            item { Text("Captcha required", style = MaterialTheme.typography.titleMedium) }
            item {
                Text(
                    if (received) "Got it, continuing…" else "Scan with your phone (same Wi-Fi) and solve it quickly:",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
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
                val firstUrl = "http://${addresses.first()}:${server.boundPort.takeIf { it > 0 } ?: 8081}/${server.code}"
                item {
                    // Scanning is much faster than typing the address, and Discord only waits so long.
                    val qr = remember(firstUrl) { qrBitmap(firstUrl, 240) }
                    if (qr != null) {
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = "QR code for the captcha page",
                            modifier = Modifier
                                .size(96.dp)
                                .background(androidx.compose.ui.graphics.Color.White)
                        )
                    }
                }
                addresses.forEach { addr ->
                    item {
                        Text(
                            "$addr:${server.boundPort.takeIf { it > 0 } ?: 8081}/${server.code}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            item {
                Text(
                    "If the page can't load the captcha, cancel and do this once in the Discord app on your phone.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Button(
                    onClick = { CaptchaBroker.complete(null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    colors = ButtonDefaults.filledTonalButtonColors()
                ) { Text("Cancel") }
            }
        }
    }
}

private fun wlanAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
}.getOrDefault(emptyList())

/** Asks Wear OS to bring Wi-Fi up (watches normally route through the phone over Bluetooth). */
private class WifiRequest(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun acquire() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {}
        }
        runCatching {
            cm?.requestNetwork(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                cb
            )
            callback = cb
        }
    }

    fun release() {
        callback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        callback = null
    }
}

private fun qrBitmap(content: String, size: Int): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i ->
        if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
}.getOrNull()
