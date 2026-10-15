package com.dihcord.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/** Accent-colour themes. Changing them takes effect immediately; the choice is persisted in [SetupPreferences]. */
object AppTheme {
    enum class Preset(val label: String, val accent: Long?) {
        DEFAULT("Default", null),
        BLURPLE("Blurple", 0xFF5865F2),
        MINT("Mint", 0xFF3BA55D),
        OCEAN("Ocean", 0xFF00A8FC),
        ROSE("Rose", 0xFFEB459E),
        AMBER("Amber", 0xFFFAA61A),
        CRIMSON("Crimson", 0xFFED4245),
        LAVENDER("Lavender", 0xFFB49CFF),
    }

    var preset by mutableStateOf(Preset.DEFAULT)
        private set
    var amoled by mutableStateOf(false)
        private set

    fun init(context: Context) {
        preset = SetupPreferences.getThemePreset(context)
        amoled = SetupPreferences.getAmoledTheme(context)
    }

    fun setPreset(context: Context, value: Preset) {
        preset = value
        SetupPreferences.setThemePreset(context, value)
    }

    fun setAmoled(context: Context, value: Boolean) {
        amoled = value
        SetupPreferences.setAmoledTheme(context, value)
    }

    /** Builds a full colour scheme from one accent colour (null keeps the stock Wear scheme). */
    fun scheme(preset: Preset, amoled: Boolean): ColorScheme {
        val base = ColorScheme()
        val accent = preset.accent?.let { Color(it) }
        var scheme = if (accent == null) base else base.copy(
            primary = accent,
            primaryDim = lerp(accent, Color.Black, 0.25f),
            primaryContainer = lerp(accent, Color.Black, 0.6f),
            onPrimary = if (accent.luminance() > 0.4f) Color.Black else Color.White,
            onPrimaryContainer = lerp(accent, Color.White, 0.75f),
            secondary = lerp(accent, Color.White, 0.35f),
            secondaryDim = lerp(accent, Color.White, 0.15f),
            secondaryContainer = lerp(accent, Color.Black, 0.72f),
            onSecondary = Color.Black,
            onSecondaryContainer = lerp(accent, Color.White, 0.8f),
            surfaceContainerLow = lerp(Color(0xFF131316), accent, 0.06f),
            surfaceContainer = lerp(Color(0xFF1E1E23), accent, 0.10f),
            surfaceContainerHigh = lerp(Color(0xFF2A2A31), accent, 0.14f),
        )
        if (amoled) {
            scheme = scheme.copy(
                background = Color.Black,
                surfaceContainerLow = Color.Black,
                surfaceContainer = Color(0xFF0F0F10),
                surfaceContainerHigh = Color(0xFF1B1B1D),
            )
        }
        return scheme
    }
}

@Composable
fun DihcordTheme(content: @Composable () -> Unit) {
    val preset = AppTheme.preset
    val amoled = AppTheme.amoled
    val colorScheme = remember(preset, amoled) { AppTheme.scheme(preset, amoled) }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
