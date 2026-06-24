package com.iguar.armedllama.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iguar.armedllama.model.ModelEntry
import com.iguar.armedllama.model.ModelState
import com.iguar.armedllama.model.MonitorUiState
import com.iguar.armedllama.model.ReleaseState
import com.iguar.armedllama.ui.components.GradientBar
import com.iguar.armedllama.ui.components.SectionLabel
import com.iguar.armedllama.ui.components.panel
import com.iguar.armedllama.ui.theme.MonitorTheme
import com.iguar.armedllama.ui.theme.MonitorType
import kotlin.math.roundToInt

// ----- Settings ------------------------------------------------------------------------------

private val CTX_OPTIONS = listOf(2048, 4096, 8192, 16384, 32768)

@Composable
fun SettingsPanel(state: MonitorUiState, onBack: () -> Unit, callbacks: MenuCallbacks) {
    val s = state.settings
    Column(modifier = Modifier.fillMaxSize()) {
        PanelHeader("Settings", onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingGroup("Inference") {
                SettingRow("Context size", "-c / --ctx-size") {
                    val idx = CTX_OPTIONS.indexOf(s.ctx).coerceAtLeast(0)
                    Stepper(
                        value = s.ctx.toString(),
                        decrementEnabled = idx > 0,
                        incrementEnabled = idx < CTX_OPTIONS.lastIndex,
                        onDecrement = { callbacks.onUpdateSettings { it.copy(ctx = CTX_OPTIONS[(idx - 1).coerceAtLeast(0)]) } },
                        onIncrement = { callbacks.onUpdateSettings { it.copy(ctx = CTX_OPTIONS[(idx + 1).coerceAtMost(CTX_OPTIONS.lastIndex)]) } },
                    )
                }
                SettingRow("CPU threads", "--threads") {
                    Stepper(
                        value = s.threads.toString(),
                        decrementEnabled = s.threads > 1,
                        incrementEnabled = s.threads < 16,
                        onDecrement = { callbacks.onUpdateSettings { it.copy(threads = (it.threads - 1).coerceAtLeast(1)) } },
                        onIncrement = { callbacks.onUpdateSettings { it.copy(threads = (it.threads + 1).coerceAtMost(16)) } },
                    )
                }
                SettingRow("GPU layers", "-ngl") {
                    Stepper(
                        value = s.gpuLayers.toString(),
                        decrementEnabled = s.gpuLayers > 0,
                        incrementEnabled = s.gpuLayers < 99,
                        onDecrement = { callbacks.onUpdateSettings { it.copy(gpuLayers = (it.gpuLayers - 1).coerceAtLeast(0)) } },
                        onIncrement = { callbacks.onUpdateSettings { it.copy(gpuLayers = (it.gpuLayers + 1).coerceAtMost(99)) } },
                    )
                }
            }

            SettingGroup("Optimizations") {
                SettingRow("Flash attention", "-fa") {
                    Toggle(s.flashAttn) { callbacks.onUpdateSettings { it.copy(flashAttn = !it.flashAttn) } }
                }
                SettingRow("Continuous batching", "--cont-batching") {
                    Toggle(s.contBatch) { callbacks.onUpdateSettings { it.copy(contBatch = !it.contBatch) } }
                }
                SettingRow("Lock model in RAM", "--mlock") {
                    Toggle(s.mlock) { callbacks.onUpdateSettings { it.copy(mlock = !it.mlock) } }
                }
            }

            SettingGroup("Network") {
                SettingRow("Server port", "--port") {
                    Stepper(
                        value = s.port.toString(),
                        decrementEnabled = s.port > 1024,
                        incrementEnabled = s.port < 65535,
                        onDecrement = { callbacks.onUpdateSettings { it.copy(port = (it.port - 1).coerceAtLeast(1024)) } },
                        onIncrement = { callbacks.onUpdateSettings { it.copy(port = (it.port + 1).coerceAtMost(65535)) } },
                    )
                }
            }
        }
    }
}

// ----- Update llama.cpp -----------------------------------------------------------------------

@Composable
fun ReleasePanel(state: MonitorUiState, onBack: () -> Unit, callbacks: MenuCallbacks) {
    val c = MonitorTheme.colors
    val r = state.release
    Column(modifier = Modifier.fillMaxSize()) {
        PanelHeader("Update llama.cpp", onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Release card
            Column(modifier = Modifier.fillMaxWidth().panel(c.panel, c.border, 16.dp).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.tag, style = MonitorType.heroNumber, color = c.text)
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(c.good.copy(alpha = 0.18f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) { Text("latest", style = MonitorType.monoCaption, color = c.good) }
                }
                Spacer(Modifier.height(4.dp))
                Text(r.repo, style = MonitorType.monoCaption, color = c.muted)
                Spacer(Modifier.height(2.dp))
                Text("${r.date} · ${r.asset} · ${r.sizeMB} MB", style = MonitorType.monoCaption, color = c.muted)
            }

            // What's new
            Column(modifier = Modifier.fillMaxWidth().panel(c.panel, c.border, 16.dp).padding(16.dp)) {
                SectionLabel("What's new")
                Spacer(Modifier.height(8.dp))
                r.whatsNew.forEach { line ->
                    Row(modifier = Modifier.padding(vertical = 3.dp)) {
                        Text("•  ", style = MonitorType.bodyLabel, color = c.accent)
                        Text(line, style = MonitorType.bodyLabel, color = c.text)
                    }
                }
            }

            // Progress + action
            if (r.state == ReleaseState.DOWNLOADING || r.state == ReleaseState.DEPLOYING) {
                Column {
                    GradientBar(
                        fraction = if (r.state == ReleaseState.DEPLOYING) 1f else r.progress,
                        startColor = c.accent,
                        endColor = c.accent2,
                        trackColor = c.ringTrack,
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (r.state == ReleaseState.DEPLOYING) "Installing binary & restarting server…"
                        else "Fetching release asset…",
                        style = MonitorType.monoCaption,
                        color = c.muted,
                    )
                }
            }

            val label = when (r.state) {
                ReleaseState.IDLE -> "Download & deploy"
                ReleaseState.DOWNLOADING -> "Downloading…"
                ReleaseState.DEPLOYING -> "Deploying…"
                ReleaseState.DEPLOYED -> "Deployed ✓"
            }
            PrimaryButton(
                label = label,
                enabled = r.state == ReleaseState.IDLE,
                color = if (r.state == ReleaseState.DEPLOYED) c.good else c.accent,
                onClick = callbacks.onStartDeploy,
            )
        }
    }
}

// ----- Download model (Hugging Face) ---------------------------------------------------------

@Composable
fun HfPanel(state: MonitorUiState, onBack: () -> Unit, callbacks: MenuCallbacks) {
    val c = MonitorTheme.colors
    Column(modifier = Modifier.fillMaxSize()) {
        PanelHeader("Download model 🤗", onBack)
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            SearchField(query = state.hfQuery, onQueryChange = callbacks.onUpdateHfQuery)
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                state.visibleModels.forEach { model ->
                    ModelCard(model) { callbacks.onDownloadModel(model.id) }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val c = MonitorTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.tile)
            .border(1.dp, c.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Search GGUF models…", style = MonitorType.bodyLabel, color = c.muted)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MonitorType.bodyLabel.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ModelCard(model: ModelEntry, onGet: () -> Unit) {
    val c = MonitorTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .panel(c.panel, c.border, 14.dp)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(model.name, style = MonitorType.bodyLabel, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                "${model.repo.substringBefore('/')} · ${model.quant} · ${model.sizeGB} GB",
                style = MonitorType.monoCaption,
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        when (model.state) {
            ModelState.IDLE -> SmallButton("Get", c.accent, onGet)
            ModelState.DOWNLOADING -> Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(96.dp)) {
                Text("${(model.progress * 100).roundToInt()}%", style = MonitorType.monoCaption, color = c.accent)
                Spacer(Modifier.height(4.dp))
                GradientBar(
                    fraction = model.progress,
                    startColor = c.accent,
                    endColor = c.accent2,
                    trackColor = c.ringTrack,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
            ModelState.INSTALLED -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = c.good, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Installed", style = MonitorType.monoCaption, color = c.good)
            }
        }
    }
}

// ----- Buttons -------------------------------------------------------------------------------

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) color else color.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MonitorType.button, color = androidx.compose.ui.graphics.Color.White)
    }
}

@Composable
private fun SmallButton(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MonitorType.button, color = androidx.compose.ui.graphics.Color.White)
    }
}
