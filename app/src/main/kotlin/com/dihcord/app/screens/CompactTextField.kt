package com.dihcord.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/**
 * Watch-sized text field. The phone Material OutlinedTextField has a 56 dp minimum height and large placeholder
 * text, which looked oversized on a watch; this one is 36 dp tall and uses the same small text as messages.
 */
@Composable
fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else 4,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = MaterialTheme.colorScheme
    val textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface)
    val shape = RoundedCornerShape(18.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.defaultMinSize(minHeight = 36.dp),
        textStyle = textStyle,
        singleLine = singleLine,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .background(colors.surfaceContainer, shape)
                    .border(1.dp, colors.outlineVariant, shape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        placeholder,
                        style = textStyle,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                inner()
            }
        }
    )
}
