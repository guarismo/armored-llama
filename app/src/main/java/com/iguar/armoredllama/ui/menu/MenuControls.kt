package com.iguar.armoredllama.ui.menu

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.iguar.armoredllama.ui.components.SectionLabel
import com.iguar.armoredllama.ui.components.panel
import com.iguar.armoredllama.ui.theme.MonitorTheme
import com.iguar.armoredllama.ui.theme.MonitorType

/** A titled group card holding setting rows. */
@Composable
fun SettingGroup(title: String, content: @Composable () -> Unit) {
    val c = MonitorTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(title, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .panel(c.panel, c.border, radius = 16.dp)
                .padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            content()
        }
    }
}

/** A single labelled row inside a [SettingGroup]: title (+subtitle) on the left, control on the right. */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    control: @Composable () -> Unit,
) {
    val c = MonitorTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MonitorType.bodyLabel, color = c.text)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MonitorType.monoCaption, color = c.muted)
            }
        }
        Spacer(Modifier.width(12.dp))
        control()
    }
}

/** `−  value  +` stepper in a tile pill. */
@Composable
fun Stepper(
    value: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    decrementEnabled: Boolean = true,
    incrementEnabled: Boolean = true,
    valueColor: Color? = null,
) {
    val c = MonitorTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(c.tile)
            .border(1.dp, c.border, RoundedCornerShape(11.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton(Icons.Filled.Remove, enabled = decrementEnabled, onClick = onDecrement)
        Text(
            text = value,
            style = MonitorType.monoCaption.copy(fontSize = MonitorType.bodyLabel.fontSize),
            color = valueColor ?: c.text,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(64.dp).padding(horizontal = 8.dp),
        )
        StepperButton(Icons.Filled.Add, enabled = incrementEnabled, onClick = onIncrement)
    }
}



@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = MonitorTheme.colors
    Box(
        modifier = Modifier
            .size(34.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) c.text else c.muted.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 46×27 pill toggle, accent when on. */
@Composable
fun Toggle(checked: Boolean, onToggle: () -> Unit) {
    val c = MonitorTheme.colors
    val trackColor by animateColorAsState(if (checked) c.accent else c.tile, label = "track")
    val knobOffset by animateDpAsState(if (checked) 21.dp else 3.dp, label = "knob")
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 27.dp)
            .clip(CircleShape)
            .background(trackColor)
            .border(1.dp, if (checked) c.accent else c.border, CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = knobOffset)
                .size(21.dp)
                .clip(CircleShape)
                .background(androidx.compose.ui.graphics.Color.White),
        )
    }
}
