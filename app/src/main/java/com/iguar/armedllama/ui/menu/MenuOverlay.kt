package com.iguar.armedllama.ui.menu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.iguar.armedllama.model.MonitorUiState
import com.iguar.armedllama.model.Panel
import com.iguar.armedllama.ui.components.SectionLabel
import com.iguar.armedllama.ui.components.panel
import com.iguar.armedllama.ui.theme.MonitorTheme
import com.iguar.armedllama.ui.theme.MonitorType

/**
 * Hosts every menu surface over the dashboard. The drawer slides in from the left over a 55%
 * scrim; Settings/Release/HF cover the screen. State is parent-owned and passed in via callbacks.
 */
@Composable
fun MenuOverlay(
    state: MonitorUiState,
    onDismiss: () -> Unit,
    onNavigate: (Panel) -> Unit,
    onBack: () -> Unit,
    callbacks: MenuCallbacks,
) {
    // Scrim + drawer
    AnimatedVisibility(
        visible = state.panel == Panel.MENU,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                .clickable(
                    indication = null,
                    interactionSource = noRippleSource(),
                    onClick = onDismiss,
                ),
        ) {
            DrawerContent(state, onNavigate)
        }
    }

    // Full-screen sub-panels
    FullScreenPanel(visible = state.panel == Panel.SETTINGS) {
        SettingsPanel(state, onBack, callbacks)
    }
    FullScreenPanel(visible = state.panel == Panel.RELEASE) {
        ReleasePanel(state, onBack, callbacks)
    }
    FullScreenPanel(visible = state.panel == Panel.HF) {
        HfPanel(state, onBack, callbacks)
    }
}

@Composable
private fun FullScreenPanel(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it / 4 }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it / 4 }) + fadeOut(),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(MonitorTheme.colors.bg)) { content() }
    }
}

@Composable
private fun DrawerContent(state: MonitorUiState, onNavigate: (Panel) -> Unit) {
    val c = MonitorTheme.colors
    AnimatedVisibility(
        visible = true,
        enter = slideInHorizontally(initialOffsetX = { -it }),
        exit = slideOutHorizontally(targetOffsetX = { -it }),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.84f)
                .widthIn(max = 308.dp)
                .background(c.panel)
                // consume clicks so they don't dismiss via the scrim
                .clickable(indication = null, interactionSource = noRippleSource()) {}
                .padding(16.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Brush.linearGradient(listOf(c.accent, c.accent2))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("≡", color = androidx.compose.ui.graphics.Color.White, style = MonitorType.title)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("llama.cpp", style = MonitorType.title, color = c.text)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Android server · ", style = MonitorType.monoCaption, color = c.muted)
                        Text("NEW ${state.release.tag}", style = MonitorType.monoCaption, color = c.good)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            DrawerRow(Icons.Filled.Settings, "Settings", "Context, threads, optimizations") { onNavigate(Panel.SETTINGS) }
            DrawerRow(Icons.Filled.Download, "Update llama.cpp", "Download & deploy latest release", badge = "NEW") { onNavigate(Panel.RELEASE) }
            DrawerRow(null, "Download model 🤗", "Browse GGUF on Hugging Face") { onNavigate(Panel.HF) }

            Spacer(Modifier.weight(1f))
            Text("Pixel 8 Pro · Android 15", style = MonitorType.monoCaption, color = c.muted)
            Text("arm64-v8a", style = MonitorType.monoCaption, color = c.muted)
        }
    }
}

@Composable
private fun DrawerRow(
    icon: ImageVector?,
    title: String,
    subtitle: String,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val c = MonitorTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(c.tile),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
            } else {
                Text("🤗", style = MonitorType.bodyLabel)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MonitorType.bodyLabel, color = c.text)
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(c.good.copy(alpha = 0.18f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(badge, style = MonitorType.monoCaption, color = c.good)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MonitorType.monoCaption, color = c.muted)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.muted)
    }
}

/** Reusable header for the full-screen sub-panels: back arrow + title. */
@Composable
fun PanelHeader(title: String, onBack: () -> Unit) {
    val c = MonitorTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(11.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.text)
        }
        Spacer(Modifier.width(6.dp))
        Text(title, style = MonitorType.title, color = c.text)
    }
}

/** Small helper so we don't repeat the MutableInteractionSource boilerplate. */
@Composable
private fun noRippleSource() = androidx.compose.runtime.remember { MutableInteractionSource() }
