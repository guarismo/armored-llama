package com.iguar.armoredllama.ui.menu

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iguar.armoredllama.model.ModelEntry
import com.iguar.armoredllama.model.ModelState
import com.iguar.armoredllama.model.MonitorUiState
import com.iguar.armoredllama.model.QuantOption
import com.iguar.armoredllama.model.CompanionOption
import com.iguar.armoredllama.server.ModelFitLevel
import com.iguar.armoredllama.model.UpdateStatus
import com.iguar.armoredllama.ui.components.GradientBar
import com.iguar.armoredllama.ui.components.panel
import com.iguar.armoredllama.ui.theme.MonitorTheme
import com.iguar.armoredllama.ui.theme.MonitorType
import kotlin.math.roundToInt

// ----- Settings ------------------------------------------------------------------------------

private val CTX_OPTIONS = listOf(2048, 4096, 8192, 16384, 32768, 65536, 131072)
private val REASONING_BUDGET_OPTIONS = listOf(0, 512, 1024, 2042, 4096, 8192)
private val CACHE_TYPE_OPTIONS = listOf("f16", "q8_0", "q5_1", "q5_0", "q4_1", "q4_0", "iq4_nl")

/** At/above this, context is impractical on a CPU-only build (minutes-long prompt ingest, SWA cache
 *  churn, RAM-heavy) — flagged in the UI. Verified on-device: 64K took ~12 min to ingest a 13K prompt. */
private const val CTX_WARN_THRESHOLD = 65536

@Composable
fun SettingsPanel(state: MonitorUiState, onBack: () -> Unit, callbacks: MenuCallbacks) {
    val s = state.settings
    val c = MonitorTheme.colors
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
                    val tooBig = s.ctx >= CTX_WARN_THRESHOLD
                    Column(horizontalAlignment = Alignment.End) {
                        Stepper(
                            value = s.ctx.toString(),
                            valueColor = if (tooBig) c.bad else null,
                            decrementEnabled = idx > 0,
                            incrementEnabled = idx < CTX_OPTIONS.lastIndex,
                            onDecrement = { callbacks.onUpdateSettings { it.copy(ctx = CTX_OPTIONS[(idx - 1).coerceAtLeast(0)]) } },
                            onIncrement = { callbacks.onUpdateSettings { it.copy(ctx = CTX_OPTIONS[(idx + 1).coerceAtMost(CTX_OPTIONS.lastIndex)]) } },
                        )
                        if (tooBig) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Impractical on CPU — very slow, RAM-heavy",
                                style = MonitorType.monoCaption,
                                color = c.bad,
                                textAlign = TextAlign.End,
                                modifier = Modifier.widthIn(max = 160.dp),
                            )
                        }
                    }
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
            }

            SettingGroup("Model") {
                SettingRow("Speculative decoding", "--model-draft / --spec-type") {
                    Toggle(s.useDraft) { callbacks.onUpdateSettings { it.copy(useDraft = !it.useDraft) } }
                }
                SettingRow("Vision (multimodal)", "--mmproj · ~1 GB RAM") {
                    Toggle(s.useMmproj) { callbacks.onUpdateSettings { it.copy(useMmproj = !it.useMmproj) } }
                }
                SettingRow("Jinja chat template", "--jinja / --no-jinja") {
                    Toggle(s.jinja) { callbacks.onUpdateSettings { it.copy(jinja = !it.jinja) } }
                }
                SettingRow("Reasoning budget", "--reasoning-budget") {
                    OptionStepper(
                        options = REASONING_BUDGET_OPTIONS,
                        value = s.reasoningBudget,
                        label = { it.toString() },
                    ) { budget ->
                        callbacks.onUpdateSettings { it.copy(reasoningBudget = budget) }
                    }
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
                SettingRow("KV cache K", "--cache-type-k") {
                    OptionStepper(CACHE_TYPE_OPTIONS, s.cacheTypeK) { cacheType ->
                        callbacks.onUpdateSettings { it.copy(cacheTypeK = cacheType) }
                    }
                }
                SettingRow("KV cache V", "--cache-type-v") {
                    OptionStepper(CACHE_TYPE_OPTIONS, s.cacheTypeV) { cacheType ->
                        callbacks.onUpdateSettings { it.copy(cacheTypeV = cacheType) }
                    }
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

@Composable
private fun <T> OptionStepper(
    options: List<T>,
    value: T,
    label: (T) -> String = { it.toString() },
    onChange: (T) -> Unit,
) {
    val idx = options.indexOf(value).coerceAtLeast(0)
    Stepper(
        value = label(value),
        decrementEnabled = idx > 0,
        incrementEnabled = idx < options.lastIndex,
        onDecrement = { onChange(options[(idx - 1).coerceAtLeast(0)]) },
        onIncrement = { onChange(options[(idx + 1).coerceAtMost(options.lastIndex)]) },
    )
}

// ----- Update llama.cpp -----------------------------------------------------------------------

@Composable
fun ReleasePanel(state: MonitorUiState, onBack: () -> Unit, callbacks: MenuCallbacks) {
    val c = MonitorTheme.colors
    val u = state.update
    val downloadedActive = u.activeTag != com.iguar.armoredllama.server.RuntimeBinaries.BUNDLED_TAG
    Column(modifier = Modifier.fillMaxSize()) {
        PanelHeader("Update llama.cpp", onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth().panel(c.panel, c.border, 16.dp).padding(16.dp)) {
                Text("Installed", style = MonitorType.monoCaption, color = c.muted)
                Spacer(Modifier.height(2.dp))
                Text(u.activeTag, style = MonitorType.heroNumber, color = c.text)
                Text("ggml-org/llama.cpp", style = MonitorType.monoCaption, color = c.muted)
            }

            val latest = u.latest
            if (latest != null) {
                var notesExpanded by remember { mutableStateOf(false) }
                Column(modifier = Modifier.fillMaxWidth().panel(c.panel, c.border, 16.dp).padding(16.dp)) {
                    val upToDate = u.status == UpdateStatus.UP_TO_DATE
                    val badge = if (upToDate) "up to date" else "available"
                    val badgeColor = if (upToDate) c.good else c.accent

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(latest.tag, style = MonitorType.heroNumber, color = c.text)
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(badgeColor.copy(alpha = 0.18f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(badge, style = MonitorType.monoCaption, color = badgeColor)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(latest.date, style = MonitorType.monoCaption, color = c.muted)
                    if (latest.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        if (notesExpanded) {
                            Text(
                                latest.notes,
                                style = MonitorType.bodyLabel,
                                color = c.text,
                            )
                        } else {
                            Text(
                                latest.notes,
                                style = MonitorType.bodyLabel,
                                color = c.text,
                                maxLines = 12,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (latest.notes.length > 200) {
                            Text(
                                text = if (notesExpanded) "Show less" else "Show more",
                                style = MonitorType.monoCaption,
                                color = c.accent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { notesExpanded = !notesExpanded }
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            if (u.status == UpdateStatus.DOWNLOADING) {
                Column {
                    GradientBar(
                        fraction = u.progress,
                        startColor = c.accent,
                        endColor = c.accent2,
                        trackColor = c.ringTrack,
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Fetching release asset...", style = MonitorType.monoCaption, color = c.muted)
                }
            }

            if (u.status == UpdateStatus.ERROR && u.error != null) {
                Text(u.error, style = MonitorType.monoCaption, color = c.bad)
            }
            if (u.status == UpdateStatus.INSTALLED) {
                Text(
                    "Installed. Stop/Start the server to run ${u.activeTag}.",
                    style = MonitorType.monoCaption,
                    color = c.good,
                )
            }

            val (label, action, enabled) = when (u.status) {
                UpdateStatus.CHECKING -> Triple("Checking...", callbacks.onCheckUpdate, false)
                UpdateStatus.UPDATE_AVAILABLE -> Triple("Download & install", callbacks.onDownloadUpdate, true)
                UpdateStatus.DOWNLOADING -> Triple("Downloading...", callbacks.onDownloadUpdate, false)
                UpdateStatus.INSTALLED -> Triple("Check again", callbacks.onCheckUpdate, true)
                else -> Triple("Check for updates", callbacks.onCheckUpdate, true)
            }
            PrimaryButton(label = label, enabled = enabled, color = c.accent, onClick = action)

            if (downloadedActive) {
                SecondaryButton(
                    label = "Remove downloaded runtime",
                    enabled = u.status != UpdateStatus.DOWNLOADING && u.status != UpdateStatus.CHECKING && !state.running,
                    color = c.bad,
                    onClick = callbacks.onRemoveDownloadedUpdate,
                )
                Text(
                    if (state.running) "Stop the server before removing the downloaded runtime."
                    else "Falls back to bundled ${com.iguar.armoredllama.server.RuntimeBinaries.BUNDLED_TAG}.",
                    style = MonitorType.monoCaption,
                    color = if (state.running) c.bad else c.muted,
                )
            }
        }
    }
}

// ----- Download model (Hugging Face) ---------------------------------------------------------

@Composable
fun HfPanel(state: MonitorUiState, onBack: () -> Unit, callbacks: MenuCallbacks) {
    val c = MonitorTheme.colors
    Column(modifier = Modifier.fillMaxSize()) {
        PanelHeader("Download model 🤗", onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp),
        ) {
            SearchField(query = state.hfQuery, onQueryChange = callbacks.onUpdateHfQuery)
            Spacer(Modifier.height(12.dp))
            if (state.hfQuery.isBlank()) {
                Text("On this phone", style = MonitorType.monoCaption, color = c.muted)
                Spacer(Modifier.height(8.dp))
            }
            if (state.hfLoading) {
                Text("Searching Hugging Face...", style = MonitorType.monoCaption, color = c.muted)
                Spacer(Modifier.height(8.dp))
            }
            state.hfError?.let { message ->
                Text(message, style = MonitorType.monoCaption, color = c.bad)
                Spacer(Modifier.height(8.dp))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                state.visibleModels.forEach { model ->
                    val local = state.hfQuery.isBlank() && model.state == ModelState.INSTALLED
                    // key(): bind row state (the delete-confirm flag) to the file, not list position.
                    key(model.file) {
                        ModelCard(
                            model = model,
                            local = local,
                            onDownloadModel = callbacks.onDownloadModel,
                            onDownloadCompanion = callbacks.onDownloadCompanion,
                            onSwitch = if (local) ({ callbacks.onSwitchModel(model.file) }) else null,
                            onDelete = if (local) ({ callbacks.onDeleteModel(model.file) }) else null,
                        )
                    }
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
private fun ModelCard(
    model: ModelEntry,
    local: Boolean,
    onDownloadModel: (String, String) -> Unit,
    onDownloadCompanion: (String, String) -> Unit,
    onSwitch: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val c = MonitorTheme.colors
    var confirmDelete by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val onGet = { onDownloadModel(model.repo, model.file) }
    val extraQuants = model.quants.size > 1
    val canExpand = !local && (extraQuants || model.companions.isNotEmpty())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .panel(c.panel, c.border, 14.dp)
            .padding(14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        model.name,
                        style = MonitorType.bodyLabel,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    FitBadge(model)
                }
                Spacer(Modifier.height(2.dp))
                val size = if (model.sizeGB > 0f) " · %.1f GB".format(model.sizeGB) else ""
                Text(
                    "${model.repo} · ${model.quant}$size",
                    style = MonitorType.monoCaption,
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    model.file,
                    style = MonitorType.monoCaption,
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    model.fit.detail,
                    style = MonitorType.monoCaption,
                    color = when (model.fit.level) {
                        ModelFitLevel.FITS -> c.good
                        ModelFitLevel.TIGHT -> c.warn
                        ModelFitLevel.TOO_LARGE -> c.bad
                        ModelFitLevel.UNKNOWN -> c.muted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            when (model.state) {
                // A quant/companion on this card is downloading — keep the headline action inert.
                ModelState.IDLE -> if (model.downloadingFile != null) {
                    Text("Get", style = MonitorType.button, color = c.muted)
                } else SmallButton("Get", c.accent, onGet)
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
                ModelState.ACTIVE -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = c.good, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("ACTIVE", style = MonitorType.monoCaption, color = c.good)
                }
                ModelState.INSTALLED -> if (onSwitch != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        SmallButton("Use", c.accent, onSwitch)
                        if (onDelete != null) {
                            Spacer(Modifier.height(6.dp))
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete ${model.file}",
                                tint = c.bad,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { confirmDelete = true },
                            )
                        }
                    }
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = c.good, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Installed", style = MonitorType.monoCaption, color = c.good)
                }
            }
        }

        if (canExpand) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (expanded) "▾ hide quants" else "▸ ${model.quants.size} quants",
                style = MonitorType.monoCaption,
                color = c.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            model.quants.forEach { q ->
                QuantRow(model = model, quant = q, onGet = { onDownloadModel(model.repo, q.file) })
            }
            if (model.companions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("─ Vision ─", style = MonitorType.monoCaption, color = c.muted)
                Spacer(Modifier.height(6.dp))
                model.companions.forEach { comp ->
                    CompanionRow(
                        model = model,
                        companion = comp,
                        onGet = { onDownloadCompanion(model.repo, comp.file) },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete model?") },
            text = {
                // freedGB includes on-disk companions (curated draft/mmproj go with the model).
                val hasCompanions = model.freedGB - model.sizeGB > 0.01f
                val companions = if (hasCompanions) " and its draft + vision files" else ""
                val freed = maxOf(model.freedGB, model.sizeGB)
                val size = if (freed > 0f) " — frees %.1f GB".format(freed) else ""
                Text(model.file + companions + size)
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete?.invoke() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FitBadge(model: ModelEntry) {
    val c = MonitorTheme.colors
    val color = when (model.fit.level) {
        ModelFitLevel.FITS -> c.good
        ModelFitLevel.TIGHT -> c.warn
        ModelFitLevel.TOO_LARGE -> c.bad
        ModelFitLevel.UNKNOWN -> c.muted
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            model.fit.label,
            style = MonitorType.monoCaption,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun QuantRow(model: ModelEntry, quant: QuantOption, onGet: () -> Unit) {
    val c = MonitorTheme.colors
    val downloading = model.downloadingFile == quant.file
    val installedFile = model.state != ModelState.IDLE && model.file == quant.file
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(quant.quant, style = MonitorType.bodyLabel, color = c.text)
                Spacer(Modifier.width(8.dp))
                Text("%.1f GB".format(quant.sizeGB), style = MonitorType.monoCaption, color = c.muted)
                Spacer(Modifier.width(8.dp))
                FitPill(quant.fit.level, quant.fit.label)
            }
        }
        Spacer(Modifier.width(12.dp))
        when {
            downloading -> Text("${(model.progress * 100).roundToInt()}%", style = MonitorType.monoCaption, color = c.accent)
            installedFile -> Text("Installed", style = MonitorType.monoCaption, color = c.good)
            else -> SmallButton("Get", c.accent, onGet)
        }
    }
}

@Composable
private fun CompanionRow(model: ModelEntry, companion: CompanionOption, onGet: () -> Unit) {
    val c = MonitorTheme.colors
    val downloading = model.downloadingFile == companion.file
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("👁 vision · ${companion.quant} · %.1f GB".format(companion.sizeGB),
            style = MonitorType.monoCaption, color = c.muted, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        if (downloading) {
            Text("${(model.progress * 100).roundToInt()}%", style = MonitorType.monoCaption, color = c.accent)
        } else {
            SmallButton("Get", c.accent, onGet)
        }
    }
}

@Composable
private fun FitPill(level: ModelFitLevel, label: String) {
    val c = MonitorTheme.colors
    val color = when (level) {
        ModelFitLevel.FITS -> c.good
        ModelFitLevel.TIGHT -> c.warn
        ModelFitLevel.TOO_LARGE -> c.bad
        ModelFitLevel.UNKNOWN -> c.muted
    }
    Box(
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(label, style = MonitorType.monoCaption, color = color, maxLines = 1)
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

@Composable
private fun SecondaryButton(label: String, enabled: Boolean, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (enabled) color else color.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MonitorType.button, color = if (enabled) color else color.copy(alpha = 0.55f))
    }
}
