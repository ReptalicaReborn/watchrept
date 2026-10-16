package com.dihcord.app.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Text
import com.dihcord.app.ImageSaver
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Full-screen image viewer. Tap to close, pinch or drag to zoom/pan, double-tap to toggle zoom; the Save button at
 * the bottom saves the image to the watch's gallery.
 * Back / swipe-back also dismisses (it is a dialog).
 */
@Composable
fun ImageViewer(url: String, contentDescription: String?, imageLoader: ImageLoader, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var viewSize by remember { mutableStateOf(IntSize.Zero) }

        /** Keeps the zoomed image covering the screen: it can't be dragged further than its overflow. */
        fun clampOffset(raw: Offset, s: Float): Offset {
            if (s <= 1f) return Offset.Zero
            val maxX = viewSize.width * (s - 1f) / 2f
            val maxY = viewSize.height * (s - 1f) / 2f
            return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
        }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var saving by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { viewSize = it }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = DOUBLE_TAP_ZOOM
                                offset = clampOffset(offset, scale)
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                        // graphicsLayer translation is applied after scaling, so the finger's pan maps 1:1.
                        offset = clampOffset(offset + pan, scale)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
                imageLoader = imageLoader,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ViewerButton(if (saving) "Saving…" else "Save") {
                    if (!saving) {
                        saving = true
                        scope.launch {
                            ImageSaver.save(context, url)
                                .onSuccess { Toast.makeText(context, "Saved to Pictures/Dihcord", Toast.LENGTH_SHORT).show() }
                                .onFailure {
                                    Toast.makeText(context, "Couldn't save the picture.", Toast.LENGTH_SHORT).show()
                                }
                            saving = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color(0xCC202124), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White)
    }
}
