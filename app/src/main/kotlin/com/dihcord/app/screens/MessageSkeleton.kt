package com.dihcord.app.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import com.dihcord.app.Perf

/** (name bar, first line, second line) widths as fractions of the row, so the placeholders look like real text. */
private val SKELETON_ROWS = listOf(
    Triple(0.30f, 0.90f, 0.55f),
    Triple(0.42f, 0.70f, 0f),
    Triple(0.26f, 0.95f, 0.80f),
    Triple(0.36f, 0.60f, 0.35f),
)

/** A softly pulsing alpha for placeholders; constant in lite mode so it costs nothing on weak chips. */
@Composable
private fun pulseAlpha(): State<Float> {
    if (Perf.lite) return remember { mutableFloatStateOf(0.6f) }
    val transition = rememberInfiniteTransition(label = "skeleton")
    return transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "skeletonAlpha"
    )
}

/**
 * Placeholder for one message (avatar, name and one or two text lines) shown while a conversation loads.
 * [index] picks one of a few preset shapes. The animation is read in the draw phase only, so it doesn't recompose.
 */
@Composable
fun MessageSkeleton(index: Int, modifier: Modifier = Modifier) {
    val (nameW, lineA, lineB) = SKELETON_ROWS[index.mod(SKELETON_ROWS.size)]
    val alpha = pulseAlpha()
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 3.dp)
            .graphicsLayer { this.alpha = alpha.value },
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(color, CircleShape)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SkeletonBar(nameW * 0.7f, 8, color)
            SkeletonBar(lineA, 8, color)
            if (lineB > 0f) SkeletonBar(lineB, 8, color)
        }
    }
}

@Composable
private fun SkeletonBar(fraction: Float, heightDp: Int, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth(fraction.coerceIn(0.05f, 1f))
            .height(heightDp.dp)
            .background(color, RoundedCornerShape(4.dp))
    )
}
