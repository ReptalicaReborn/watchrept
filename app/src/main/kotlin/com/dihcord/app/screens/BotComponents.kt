package com.dihcord.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.dihcord.app.api.MessageComponent

/** Discord's button colours: blurple, grey, green, red; links are grey with an arrow. */
private fun buttonColor(style: Int): Color = when (style) {
    1 -> Color(0xFF5865F2)
    3 -> Color(0xFF248046)
    4 -> Color(0xFFDA373C)
    else -> Color(0xFF4E5058)
}

/**
 * A bot message's buttons, menus and newer layout blocks, wrapped to the watch width. Tapping a
 * button or menu calls [onClick] with it; disabled ones are dimmed and do nothing.
 */
@Composable
internal fun MessageComponents(components: List<MessageComponent>, onClick: (MessageComponent) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        components.forEach { ComponentBlock(it, onClick) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComponentBlock(c: MessageComponent, onClick: (MessageComponent) -> Unit) {
    when (c) {
        is MessageComponent.Row -> FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) { c.children.forEach { ComponentBlock(it, onClick) } }

        is MessageComponent.Button -> {
            val label = listOfNotNull(c.emoji?.takeIf { it.id == null }?.name, c.label).joinToString(" ").ifBlank { "•" }
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(buttonColor(c.style).copy(alpha = if (c.disabled) 0.4f else 1f))
                    .clickable(enabled = !c.disabled) { onClick(c) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, color = Color.White.copy(alpha = if (c.disabled) 0.6f else 1f), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.isLink) Text(" ↗", color = Color.White, fontSize = 11.sp)
            }
        }

        is MessageComponent.Select -> Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .clickable(enabled = !c.disabled && c.type == 3 && c.options.isNotEmpty()) { onClick(c) }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                c.placeholder ?: if (c.type == 3) "Choose an option" else "Pick on your phone",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }

        is MessageComponent.Text -> Text(
            stripMarkdownLight(c.content),
            style = MaterialTheme.typography.bodySmall
        )

        is MessageComponent.Section -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) { c.texts.forEach { Text(stripMarkdownLight(it), style = MaterialTheme.typography.bodySmall) } }
            c.accessory?.let { ComponentBlock(it, onClick) }
        }

        is MessageComponent.Container -> {
            val accent = c.accentColor?.let { Color(0xFF000000 or it.toLong()) } ?: MaterialTheme.colorScheme.outlineVariant
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
                    .padding(start = 9.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) { c.children.forEach { ComponentBlock(it, onClick) } }
        }
    }
}

/** Bot text blocks use markdown; on the watch the marks are dropped and headings kept as plain lines. */
private fun stripMarkdownLight(text: String): String =
    text.lines().joinToString("\n") { it.replace(Regex("^#{1,3} "), "").replace(Regex("(\\*\\*|__|~~)"), "") }
