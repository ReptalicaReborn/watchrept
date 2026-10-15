package com.dihcord.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.wear.compose.material3.ColorScheme
import com.dihcord.app.AppTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.IconButtonColors
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/*
 * Shared building blocks so every screen uses the same title and section styling. Titles sit at the top of a
 * round screen, where it is narrowest, so they are limited to ~80% of the width and ellipsized.
 */

/** The title at the top of a screen. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    val base = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Text(
        text = text,
        // Pride themes paint titles in both accent colours.
        style = if (AppTheme.preset.isPride) base.copy(brush = accentBrush()) else base,
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
        color = if (AppTheme.preset.isPride) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp)
    )
}

/** A spinner centred across the full list width (a bare indicator in a list item drifts off-centre). */
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
    }
}

/** A round preview of a theme: the flag's stripes for pride themes, the accent colour otherwise. */
@Composable
fun ThemeSwatch(
    preset: AppTheme.Preset,
    size: Dp,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    var m: Modifier = Modifier
        .size(size)
        .clip(CircleShape)
    if (selected) m = m.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    if (preset.isPride) {
        Column(m) {
            preset.stripes.forEach { c ->
                Box(Modifier.fillMaxWidth().weight(1f).background(Color(c)))
            }
        }
    } else {
        Box(m.background(preset.accent?.let { Color(it) } ?: ColorScheme().primary))
    }
}

/** A thin flag bar shown under the Home title while a pride theme is active; nothing otherwise. */
@Composable
fun PrideBar(modifier: Modifier = Modifier) {
    val stripes = AppTheme.preset.stripes
    if (stripes.isEmpty()) return
    Row(
        modifier
            .fillMaxWidth(0.4f)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
    ) {
        stripes.forEach { c -> Box(Modifier.weight(1f).fillMaxSize().background(Color(c))) }
    }
}

/** A left-to-right gradient of the theme's two accent colours (primary, secondary). */
@Composable
fun accentBrush(): Brush =
    Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))

/**
 * Colours for every other filled icon button: the second accent colour on pride themes, so both flag colours show
 * (e.g. purple and yellow for nonbinary), and the normal colours otherwise.
 */
@Composable
fun altIconButtonColors(): IconButtonColors =
    if (AppTheme.preset.isPride) IconButtonDefaults.filledIconButtonColors(
        containerColor = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary
    ) else IconButtonDefaults.filledIconButtonColors()
