package com.iguar.armoredllama.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iguar.armoredllama.ui.theme.MonitorTheme
import com.iguar.armoredllama.ui.theme.MonitorType

/**
 * The top bar shared by every layout: hamburger + status dot + `llama-server` + model subline
 * on the left, START/STOP on the right.
 */
@Composable
fun TopBar(
    running: Boolean,
    host: String,
    modelFile: String,
    onMenu: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MonitorTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // weight(1f) lets the title block grow into leftover space so the button always
        // gets its natural width and "START"/"STOP" never wraps to one letter per line.
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            // Hamburger 38x38, r=11, hairline border, panel bg
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(c.panel)
                    .border(1.dp, c.border, RoundedCornerShape(11.dp))
                    .clickable(onClick = onMenu),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Menu, contentDescription = "Open menu", tint = c.text, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(11.dp))
            // Status dot with glow
            val dotColor = if (running) c.good else c.bad
            Canvas(modifier = Modifier.size(14.dp)) {
                val r = 4.dp.toPx()
                drawCircle(color = dotColor.copy(alpha = 0.35f), radius = r + 3.dp.toPx())
                drawCircle(color = dotColor, radius = r)
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("llama-server", style = MonitorType.title, color = c.text, maxLines = 1)
                Text(
                    text = "$host · $modelFile",
                    style = MonitorType.monoCaption,
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        StartStopButton(running = running, onToggle = onToggle)
    }
}

@Composable
private fun StartStopButton(running: Boolean, onToggle: () -> Unit) {
    val c = MonitorTheme.colors
    val color = if (running) c.bad else c.good
    val shape = RoundedCornerShape(11.dp)
    Row(
        modifier = Modifier
            // colored glow per README (button shadow uses the button color)
            .shadow(elevation = 10.dp, shape = shape, spotColor = color, ambientColor = color)
            .clip(shape)
            .background(color)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.PowerSettingsNew,
            contentDescription = null,
            tint = onColor(color),
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (running) "STOP" else "START",
            style = MonitorType.button,
            color = onColor(color),
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun onColor(bg: Color): Color = Color.White
