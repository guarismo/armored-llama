package com.iguar.armoredllama.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The design tokens from the handoff README ("Design Tokens" table), modelled as a
 * palette that flips between dark and light. These — not Material's color roles — are the
 * source of truth for color, so screens read them via [LocalMonitorColors] / `MonitorTheme.colors`.
 */
@Immutable
data class MonitorColors(
    val bg: Color,
    val panel: Color,
    val tile: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accent2: Color,
    val good: Color,
    val bad: Color,
    val warn: Color,
    val temp: Color,
    val ringTrack: Color,
    val logBg: Color,
    val logBorder: Color,
    val logTs: Color,
    val logBody: Color,
    val isDark: Boolean,
)

val DarkTokens = MonitorColors(
    bg = Color(0xFF0B0F14),
    panel = Color(0xFF141B24),
    tile = Color(0xFF101720),
    border = Color(0xFF222D3A),
    text = Color(0xFFE8EEF6),
    muted = Color(0xFF8593A6),
    accent = Color(0xFF46C2FF),
    accent2 = Color(0xFF9A7BFF),
    good = Color(0xFF34D399),
    bad = Color(0xFFFB7185),
    warn = Color(0xFFFBBF24),
    temp = Color(0xFFFF8A5C),
    ringTrack = Color(0xFF222D3A),
    logBg = Color(0xFF080C11),
    logBorder = Color(0xFF1B2530),
    logTs = Color(0xFF5A6B80),
    logBody = Color(0xFFC4D2E4),
    isDark = true,
)

val LightTokens = MonitorColors(
    bg = Color(0xFFF3F5F9),
    panel = Color(0xFFFFFFFF),
    tile = Color(0xFFF6F8FC),
    border = Color(0xFFE3E8F0),
    text = Color(0xFF16202C),
    muted = Color(0xFF64718A),
    accent = Color(0xFF0A84FF),
    accent2 = Color(0xFF6D44FF),
    good = Color(0xFF0F9D58),
    bad = Color(0xFFE5484D),
    warn = Color(0xFFC98A00),
    temp = Color(0xFFE8590C),
    ringTrack = Color(0xFFE6EBF2),
    // log window stays dark in both themes (per token table)
    logBg = Color(0xFF0C1118),
    logBorder = Color(0xFF1B2530),
    logTs = Color(0xFF5F7088),
    logBody = Color(0xFFCDD9E8),
    isDark = false,
)

/** Traffic-light dots in the log header (fixed regardless of theme). */
val TrafficRed = Color(0xFFFF5F57)
val TrafficYellow = Color(0xFFFEBC2E)
val TrafficGreen = Color(0xFF28C840)

val LocalMonitorColors = staticCompositionLocalOf { DarkTokens }
