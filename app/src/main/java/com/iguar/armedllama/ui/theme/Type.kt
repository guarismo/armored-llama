package com.iguar.armedllama.ui.theme

import androidx.compose.ui.text.font.FontFamily

/**
 * The handoff calls for two families:
 *   - Space Grotesk  → UI (labels, titles, buttons)
 *   - JetBrains Mono → all numbers, logs, technical labels
 *
 * To keep the project buildable without bundling binary font assets, these map to the
 * platform's default sans and monospace families. To reach full hi-fi, drop the real
 * `.ttf`s into `res/font/` and swap these two lines, e.g.:
 *
 *   val DisplayFontFamily = FontFamily(Font(R.font.space_grotesk_medium, FontWeight.Medium), ...)
 *   val MonoFontFamily    = FontFamily(Font(R.font.jetbrains_mono_medium, FontWeight.Medium), ...)
 *
 * Everything else (weights/sizes/letter-spacing) is applied at the call sites via [MonitorType].
 */
val DisplayFontFamily: FontFamily = FontFamily.SansSerif   // → Space Grotesk
val MonoFontFamily: FontFamily = FontFamily.Monospace      // → JetBrains Mono
