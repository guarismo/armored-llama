package com.iguar.armoredllama.ui.console

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iguar.armoredllama.model.MonitorUiState
import com.iguar.armoredllama.ui.components.GradientBar
import com.iguar.armoredllama.ui.components.LogWindow
import com.iguar.armoredllama.ui.components.MutedCaption
import com.iguar.armoredllama.ui.components.ProgressRing
import com.iguar.armoredllama.ui.components.SectionLabel
import com.iguar.armoredllama.ui.components.Sparkline
import com.iguar.armoredllama.ui.components.panel
import com.iguar.armoredllama.ui.components.tile
import com.iguar.armoredllama.ui.theme.MonitorTheme
import com.iguar.armoredllama.ui.theme.MonitorType
import kotlin.math.roundToInt

/**
 * Layout **A · Console**: RAM hero card → 4-up stat row → CPU core grid → log feed.
 */
@Composable
fun ConsoleDashboard(state: MonitorUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RamHeroCard(state)
        StatRow(state)
        CoreGrid(state)
        LogWindow(
            logs = state.logs,
            ppRate = state.ppLabel,
            genRate = state.genLabel,
            height = 184.dp,
        )
    }
}

@Composable
private fun RamHeroCard(state: MonitorUiState) {
    val c = MonitorTheme.colors
    val m = state.metrics
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .panel(c.panel, c.border, radius = 20.dp)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SectionLabel("Memory")
            Spacer(Modifier.weight(1f))
            Text(
                "${m.ramUsed.roundToInt()} / ${m.ramTotal.roundToInt()} MB",
                style = MonitorType.heroNumber,
                color = c.text,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "${m.ramFree.roundToInt()} MB free",
            style = MonitorType.monoCaption,
            color = c.accent,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(
                progress = m.ramPct,
                diameter = 104.dp,
                color = c.accent2,
                trackColor = c.ringTrack,
                strokeWidth = 10.dp,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(m.ramPct * 100).roundToInt()}%", style = MonitorType.ringCenter, color = c.text)
                    MutedCaption("RAM")
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                MutedCaption("RAM over time")
                Spacer(Modifier.height(6.dp))
                Sparkline(
                    samples = state.histories.ram,
                    color = c.accent2,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }
    }
}

@Composable
private fun StatRow(state: MonitorUiState) {
    val c = MonitorTheme.colors
    val m = state.metrics
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatTile("CPU", "${m.cpu.roundToInt()}", "%", c.text, Modifier.weight(1f))
        StatTile("TEMP", "${m.temp.roundToInt()}", "°C", c.temp, Modifier.weight(1f))
        StatTile("TOK/S", "${m.tps.roundToInt()}", "gen", c.accent2, Modifier.weight(1f))
        StatTile("PP", "${m.pp.roundToInt()}", "t/s", c.accent, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    unit: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    subline: String? = null,
) {
    val c = MonitorTheme.colors
    Column(
        modifier = modifier
            .tile(c.panel, c.border, radius = 14.dp)
            .padding(horizontal = 10.dp, vertical = 11.dp),
    ) {
        SectionLabel(label)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MonitorType.statNumber, color = valueColor)
            Spacer(Modifier.width(3.dp))
            Text(unit, style = MonitorType.monoCaption, color = c.muted, modifier = Modifier.padding(bottom = 2.dp))
        }
        if (subline != null) {
            MutedCaption(subline)
        }
    }
}

@Composable
private fun CoreGrid(state: MonitorUiState) {
    val c = MonitorTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .panel(c.panel, c.border, radius = 16.dp)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SectionLabel("CPU Cores")
            Spacer(Modifier.weight(1f))
            MutedCaption("avg ${state.metrics.avgCoreMhz} MHz")
        }
        Spacer(Modifier.height(10.dp))
        val cores = state.metrics.cores
        val maxMhz = state.metrics.maxCoreMhz
        // 2-column grid: render in pairs of rows.
        cores.chunked(2).forEachIndexed { rowIdx, pair ->
            if (rowIdx > 0) Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                pair.forEachIndexed { colIdx, freq ->
                    CoreTile(index = rowIdx * 2 + colIdx, freq = freq, maxMhz = maxMhz, modifier = Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CoreTile(index: Int, freq: Float, maxMhz: Float, modifier: Modifier = Modifier) {
    val c = MonitorTheme.colors
    Column(
        modifier = modifier
            .tile(c.tile, c.border, radius = 12.dp)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Core $index", style = MonitorType.bodyLabel.copy(fontWeight = FontWeight.Medium), color = c.muted)
            Spacer(Modifier.weight(1f))
            Text("${freq.roundToInt()} MHz", style = MonitorType.monoCaption, color = c.text)
        }
        Spacer(Modifier.height(6.dp))
        GradientBar(
            fraction = (freq / maxMhz),
            startColor = c.accent,
            endColor = c.accent2,
            trackColor = c.ringTrack,
            modifier = Modifier.fillMaxWidth().height(5.dp),
        )
    }
}
