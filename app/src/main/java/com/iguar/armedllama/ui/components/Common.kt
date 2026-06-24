package com.iguar.armedllama.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import com.iguar.armedllama.ui.theme.MonitorTheme
import com.iguar.armedllama.ui.theme.MonitorType

/** Card surface: token panel fill + 1px hairline border + rounded corners. */
fun Modifier.panel(
    fill: Color,
    border: Color,
    radius: Dp = 16.dp,
): Modifier = this
    .clip(RoundedCornerShape(radius))
    .background(fill)
    .border(1.dp, border, RoundedCornerShape(radius))

/** Inset tile (used for stat tiles, stepper pills). */
fun Modifier.tile(
    fill: Color,
    border: Color,
    radius: Dp = 14.dp,
): Modifier = this.panel(fill, border, radius)

/** Uppercase section / stat label in the muted color. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MonitorType.sectionLabel,
        color = MonitorTheme.colors.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** A mono caption in the muted color (sublines, avg freq, etc.). */
@Composable
fun MutedCaption(text: String, modifier: Modifier = Modifier, color: Color = MonitorTheme.colors.muted) {
    Text(
        text = text,
        style = MonitorType.monoCaption,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Helper to render a number + a smaller trailing unit on the same baseline-ish row. */
@Composable
fun StatValue(value: String, unit: String, valueColor: Color, modifier: Modifier = Modifier) {
    Text(
        text = value,
        style = MonitorType.statNumber,
        color = valueColor,
        maxLines = 1,
        modifier = modifier,
    )
    if (unit.isNotEmpty()) {
        Text(
            text = " $unit",
            style = MonitorType.monoCaption.copy(fontWeight = FontWeight.Medium),
            color = MonitorTheme.colors.muted,
        )
    }
}
