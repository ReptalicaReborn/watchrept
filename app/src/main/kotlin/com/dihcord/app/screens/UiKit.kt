package com.dihcord.app.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/*
 * Shared building blocks so every screen uses the same title and section styling. Titles sit at the top of a
 * round screen, where it is narrowest, so they are limited to ~80% of the width and ellipsized.
 */

/** The title at the top of a screen. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth(0.8f)
            .padding(bottom = 2.dp)
    )
}

/** A small caption under a [ScreenTitle] (e.g. the channel a thread list belongs to). */
@Composable
fun ScreenSubtitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(0.8f)
    )
}

/** A centred section label inside a list (centred so the round edge never clips it). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 1.sp),
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp)
    )
}
