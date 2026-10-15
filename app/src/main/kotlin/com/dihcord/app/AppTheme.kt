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
    /**
     * A theme. [accent] drives the primary colours; pride themes also set [second]/[third] (used for the secondary
     * and tertiary roles) and list their flag's [stripes], drawn as the swatch in Settings and the bar on Home.
     */
    enum class Preset(
        val label: String,
        val accent: Long?,
        val second: Long? = null,
        val third: Long? = null,
        val stripes: List<Long> = emptyList(),
    ) {
        DEFAULT("Default", null),
        BLURPLE("Blurple", 0xFF5865F2),
        MINT("Mint", 0xFF3BA55D),
        OCEAN("Ocean", 0xFF00A8FC),
        ROSE("Rose", 0xFFEB459E),
        AMBER("Amber", 0xFFFAA61A),
        CRIMSON("Crimson", 0xFFED4245),
        LAVENDER("Lavender", 0xFFB49CFF),
        PRIDE(
            "Pride", 0xFFFF8C00, 0xFFFFED00, 0xFF4F7BFF,
            listOf(0xFFE40303, 0xFFFF8C00, 0xFFFFED00, 0xFF008026, 0xFF24408E, 0xFF732982)
        ),
        TRANS(
            "Trans", 0xFF5BCEFA, 0xFFF5A9B8, 0xFFFFFFFF,
            listOf(0xFF5BCEFA, 0xFFF5A9B8, 0xFFFFFFFF, 0xFFF5A9B8, 0xFF5BCEFA)
        ),
        NONBINARY(
            "Nonbinary", 0xFFB27FE0, 0xFFFCF434, 0xFFFFFFFF,
            listOf(0xFFFCF434, 0xFFFFFFFF, 0xFF9C59D1, 0xFF2C2C2C)
        ),
        LESBIAN(
            "Lesbian", 0xFFEF7627, 0xFFD162A4, 0xFFFF9A56,
            listOf(0xFFD52D00, 0xFFEF7627, 0xFFFF9A56, 0xFFFFFFFF, 0xFFD162A4, 0xFFB55690, 0xFFA30262)
        ),
        GAY(
            "Gay", 0xFF26CEAA, 0xFF98E8C1, 0xFF7BADE2,
            listOf(0xFF078D70, 0xFF26CEAA, 0xFF98E8C1, 0xFFFFFFFF, 0xFF7BADE2, 0xFF5049CC, 0xFF3D1A78)
        ),
        BI(
            "Bi", 0xFFE0408A, 0xFFB57BB2, 0xFF4A7BFF,
            listOf(0xFFD60270, 0xFFD60270, 0xFF9B4F96, 0xFF0038A8, 0xFF0038A8)
        ),
        PAN(
            "Pan", 0xFFFF218C, 0xFFFFD800, 0xFF21B1FF,
            listOf(0xFFFF218C, 0xFFFFD800, 0xFF21B1FF)
        ),
        ACE(
            "Ace", 0xFFB04BB0, 0xFFA3A3A3, 0xFFFFFFFF,
            listOf(0xFF000000, 0xFFA3A3A3, 0xFFFFFFFF, 0xFF800080)
        ),
        ARO(
            "Aro", 0xFF3DA542, 0xFFA7D379, 0xFFFFFFFF,
            listOf(0xFF3DA542, 0xFFA7D379, 0xFFFFFFFF, 0xFFA9A9A9, 0xFF000000)
        ),
        GENDERFLUID(
            "Genderfluid", 0xFFFF76A4, 0xFFD35EE6, 0xFF6573FF,
            listOf(0xFFFF76A4, 0xFFFFFFFF, 0xFFC011D7, 0xFF000000, 0xFF2F3CBE)
        ),
        AGENDER(
            "Agender", 0xFFB8F483, 0xFFBCC4C7, 0xFFFFFFFF,
            listOf(0xFF000000, 0xFFBCC4C7, 0xFFFFFFFF, 0xFFB8F483, 0xFFFFFFFF, 0xFFBCC4C7, 0xFF000000)
        ),
        INTERSEX(
            "Intersex", 0xFFFFD800, 0xFFB063E0, 0xFFFFE97A,
            listOf(0xFFFFD800, 0xFF7902AA, 0xFFFFD800)
        );

        val isPride: Boolean get() = stripes.isNotEmpty()
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
        val second = preset.second?.let { Color(it) }
        if (second != null) {
            scheme = scheme.copy(
                secondary = second,
                secondaryDim = lerp(second, Color.Black, 0.2f),
                secondaryContainer = lerp(second, Color.Black, 0.7f),
                onSecondary = if (second.luminance() > 0.4f) Color.Black else Color.White,
                onSecondaryContainer = lerp(second, Color.White, 0.8f),
            )
        }
        val third = preset.third?.let { Color(it) }
        if (third != null) {
            scheme = scheme.copy(
                tertiary = third,
                tertiaryDim = lerp(third, Color.Black, 0.2f),
                tertiaryContainer = lerp(third, Color.Black, 0.7f),
                onTertiary = if (third.luminance() > 0.4f) Color.Black else Color.White,
                onTertiaryContainer = lerp(third, Color.White, 0.8f),
            )
        }
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
