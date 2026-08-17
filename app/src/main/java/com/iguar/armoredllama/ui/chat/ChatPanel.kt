package com.iguar.armoredllama.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.iguar.armoredllama.model.MonitorUiState
import com.iguar.armoredllama.ui.menu.PanelHeader
import com.iguar.armoredllama.ui.theme.MonitorTheme

/**
 * Full-screen Chat panel hosting the retained llama-server web UI WebView. The WebView instance
 * outlives this composable (see ChatWebViewHolder), so backing out mid-generation doesn't cut
 * the reply off. imePadding keeps the web UI's input above the keyboard.
 */
@Composable
fun ChatPanel(state: MonitorUiState, onBack: () -> Unit) {
    val c = MonitorTheme.colors
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        PanelHeader("Chat", onBack) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "Reload chat",
                tint = c.text,
                modifier = Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .clickable { ChatWebViewHolder.reload() }
                    .size(28.dp),
            )
        }
        AndroidView(
            factory = { ctx -> ChatWebViewHolder.obtain(ctx, chatUrl(state.settings.port), state.serverEpoch) },
            update = { ChatWebViewHolder.onEpoch(state.serverEpoch) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}
