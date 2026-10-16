package com.dihcord.app.screens

import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.*
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only the newest photos are listed, to keep the query and grid cheap on a watch. */
private const val MAX_PICKER_IMAGES = 200

data class MediaImage(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val dateAdded: Long
)

@Composable
fun PhotoPickerScreen(
    imageLoader: ImageLoader,
    onImageSelected: (Uri, String) -> Unit,  // uri, mimeType
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    BackHandler { onDismiss() }

    var images by remember { mutableStateOf<List<MediaImage>>(emptyList()) }
    var permGranted by remember { mutableStateOf(false) }
    // Distinguishes "still querying MediaStore" from "query finished with no photos".
    var loaded by remember { mutableStateOf(false) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(Unit) {
        permGranted = if (android.os.Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permGranted = granted
    }

    LaunchedEffect(permGranted) {
        if (!permGranted) return@LaunchedEffect
        loaded = false
        images = withContext(Dispatchers.IO) {
            val result = mutableListOf<MediaImage>()
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATE_ADDED
            )
            // No LIMIT in sortOrder (rejected on Android 11+); stop iterating at the cap instead.
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            runCatching {
                context.contentResolver.query(collection, projection, null, null, sortOrder)
                    ?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                        val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                        while (result.size < MAX_PICKER_IMAGES && cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val uri =
                                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                            result.add(
                                MediaImage(
                                    id = id,
                                    uri = uri,
                                    displayName = cursor.getString(nameCol) ?: "image",
                                    mimeType = cursor.getString(mimeCol) ?: "image/jpeg",
                                    dateAdded = cursor.getLong(dateCol)
                                )
                            )
                        }
                    }
            }
            result
        }
        loaded = true
    }

    val confirmUri = selectedUri
    if (confirmUri != null) {
        val confirmImg = images.find { it.uri == confirmUri }
        ImageConfirmScreen(
            uri = confirmUri,
            mimeType = confirmImg?.mimeType ?: "image/jpeg",
            imageLoader = imageLoader,
            onConfirm = { uri, mime ->
                onImageSelected(uri, mime)
            },
            onCancel = { selectedUri = null }
        )
        return
    }

    if (!permGranted) {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        PhotoMessageContent(
            iconRes = com.dihcord.app.R.drawable.image,
            title = "Allow photo access",
            hint = "So you can send photos from the watch.",
            primary = "Allow" to { permLauncher.launch(perm) },
            onClose = onDismiss
        )
        return
    }

    if (images.isEmpty()) {
        if (!loaded) {
            PhotoMessageContent(null, "Loading photos…", null, null, onDismiss)
        } else {
            PhotoMessageContent(com.dihcord.app.R.drawable.image, "No photos on this watch", "Photos you take or sync show up here.", null, onDismiss)
        }
        return
    }

    PhotoGridContent(images, imageLoader, onPick = { selectedUri = it.uri }, onClose = onDismiss)
}

/** A message with an icon, and an optional button, on the new list; the close button at the bottom. */
@Composable
internal fun PhotoMessageContent(
    iconRes: Int?,
    title: String,
    hint: String?,
    primary: Pair<String, () -> Unit>?,
    onClose: () -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(
        scrollState = state,
        edgeButton = {
            EdgeButton(onClick = primary?.second ?: onClose, colors = if (primary != null) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()) {
                Text(primary?.first ?: "Back")
            }
        }
    ) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item {
                if (iconRes == null) LoadingIndicator() else EmptyState(iconRes, title, hint)
            }
            if (iconRes == null) item {
                Text(title, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** The newest photos in rows of three that shrink at the round edge; tap one to preview it. */
@Composable
internal fun PhotoGridContent(images: List<MediaImage>, imageLoader: ImageLoader, onPick: (MediaImage) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(
        scrollState = state,
        edgeButton = { EdgeButton(onClick = onClose, colors = ButtonDefaults.filledTonalButtonColors()) { Text("Close") } }
    ) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ScreenTitle("Choose a photo") }
            val rows = images.chunked(3)
            items(rows.size, key = { rows[it].first().id }) { r ->
                Row(
                    Modifier.fillMaxWidth().edgeMorph(this, spec).padding(vertical = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)
                ) {
                    rows[r].forEach { img ->
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(img.uri).size(120).crossfade(true).build(),
                            imageLoader = imageLoader,
                            contentDescription = img.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .clickable { onPick(img) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageConfirmScreen(
    uri: Uri,
    mimeType: String,
    imageLoader: ImageLoader,
    onConfirm: (Uri, String) -> Unit,
    onCancel: () -> Unit
) {
    BackHandler { onCancel() }
    val context = LocalContext.current
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(
        scrollState = state,
        edgeButton = { EdgeButton(onClick = { onConfirm(uri, mimeType) }) { Text("Send") } }
    ) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ScreenTitle("Send this photo?") }
            item {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(uri).crossfade(true).build(),
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp))
                )
            }
            item { Text("Swipe right to pick another", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        }
    }
}
