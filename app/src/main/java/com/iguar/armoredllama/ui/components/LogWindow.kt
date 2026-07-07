package com.iguar.armoredllama.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.iguar.armoredllama.model.LogLine
import com.iguar.armoredllama.ui.theme.MonitorTheme
import com.iguar.armoredllama.ui.theme.MonitorType
import com.iguar.armoredllama.ui.theme.TrafficGreen
import com.iguar.armoredllama.ui.theme.TrafficRed
import com.iguar.armoredllama.ui.theme.TrafficYellow

/**
 * Terminal-style streaming log window. Dark in both themes; auto-scrolls to the newest line.
 */
@Composable
fun LogWindow(
    logs: List<LogLine>,
    ppRate: Int,
    genRate: Int,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val c = MonitorTheme.colors
    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new lines (README behaviour).
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.logBg)
            .border(1.dp, c.logBorder, RoundedCornerShape(16.dp)),
    ) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrafficDot(TrafficRed)
            Spacer(Modifier.width(6.dp))
            TrafficDot(TrafficYellow)
            Spacer(Modifier.width(6.dp))
            TrafficDot(TrafficGreen)
            Spacer(Modifier.width(10.dp))
            Text("server.log", style = MonitorType.monoCaption, color = Color(0xFF7E8DA3))
            Spacer(Modifier.weight(1f))
            Text("$ppRate pp · $genRate gen", style = MonitorType.monoCaption, color = Color(0xFF34D399))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(logs) { line ->
                Text(text = renderLine(line, c.logTs, c.logBody), style = MonitorType.log)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

private fun renderLine(line: LogLine, tsColor: Color, bodyColor: Color): AnnotatedString =
    buildAnnotatedString {
        withStyle(SpanStyle(color = tsColor)) { append(line.time) }
        append("  ")
        withStyle(SpanStyle(color = bodyColor)) { append(line.body) }
    }

@Composable
private fun TrafficDot(color: Color) {
    Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(color))
}
