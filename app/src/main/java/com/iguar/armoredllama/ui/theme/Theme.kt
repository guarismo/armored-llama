package com.iguar.armoredllama.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Convenience accessor: `MonitorTheme.colors.accent`, etc. */
object MonitorTheme {
    val colors: MonitorColors
        @Composable get() = LocalMonitorColors.current
}

/**
 * App theme. Follows the system light/dark setting (per README: "follow the system theme").
 * It publishes the design tokens via [LocalMonitorColors] and also seeds a minimal Material3
 * color scheme so stock ripples / selection colors look right.
 */
@Composable
fun ArmoredLlamaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val tokens = if (darkTheme) DarkTokens else LightTokens

    val material = if (darkTheme) {
        darkColorScheme(
            primary = tokens.accent,
            secondary = tokens.accent2,
            background = tokens.bg,
            surface = tokens.panel,
            onPrimary = tokens.bg,
            onBackground = tokens.text,
            onSurface = tokens.text,
            error = tokens.bad,
        )
    } else {
        lightColorScheme(
            primary = tokens.accent,
            secondary = tokens.accent2,
            background = tokens.bg,
            surface = tokens.panel,
            onPrimary = tokens.panel,
            onBackground = tokens.text,
            onSurface = tokens.text,
            error = tokens.bad,
        )
    }

    CompositionLocalProvider(LocalMonitorColors provides tokens) {
        MaterialTheme(colorScheme = material, content = content)
    }
}

/**
 * Named text styles distilled from the README typography section. Color is intentionally left
 * unset here — call sites apply the token color appropriate to context.
 */
object MonitorType {
    /** Big hero numbers (RAM `used/total`). */
    val heroNumber = TextStyle(fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp)
    /** Ring centre percentages. */
    val ringCenter = TextStyle(fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold, fontSize = 27.sp)
    /** Stat-tile numbers. */
    val statNumber = TextStyle(fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    /** `llama-server` title. */
    val title = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    /** Section labels ("MEMORY", "CPU Cores"). */
    val sectionLabel = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.08.em)
    /** Card / row body labels. */
    val bodyLabel = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    /** Mono captions / sublines / freqs. */
    val monoCaption = TextStyle(fontFamily = MonoFontFamily, fontWeight = FontWeight.Medium, fontSize = 10.5.sp)
    /** Start/Stop button label. */
    val button = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.07.em)
    /** Log lines. */
    val log = TextStyle(fontFamily = MonoFontFamily, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 15.sp)
}
