package com.dihcord.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.dihcord.app.api.MentionCandidate

/** Tappable "@name" / "#channel" / ":emoji:" suggestions shown above the message box while typing one. */
@Composable
fun MentionSuggestions(candidates: List<MentionCandidate>, onPick: (MentionCandidate) -> Unit) {
    if (candidates.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        candidates.forEach { c ->
            if (c.kind == MentionCandidate.Kind.EMOJI) {
                EmojiSuggestion(c, onPick)
                return@forEach
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { onPick(c) }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    c.shown,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (c.username.isNotBlank() && c.username != c.displayName) {
                    Text(
                        c.username,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** One emoji row: the emoji itself, then ":name:" and (for custom emoji) its server. */
@Composable
private fun EmojiSuggestion(c: MentionCandidate, onPick: (MentionCandidate) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { onPick(c) }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (c.imageUrl != null) {
            AsyncImage(model = c.imageUrl, contentDescription = c.displayName, modifier = Modifier.size(20.dp))
        } else {
            Text(c.shown, fontSize = 16.sp, modifier = Modifier.size(20.dp).wrapContentSize())
        }
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                ":${c.displayName}:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (c.username.isNotBlank()) {
                Text(
                    c.username,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
