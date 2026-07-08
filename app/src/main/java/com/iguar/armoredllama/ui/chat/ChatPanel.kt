package com.iguar.armoredllama.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.iguar.armoredllama.model.MonitorUiState
import com.iguar.armoredllama.ui.menu.PanelHeader

/**
 * Full-screen Chat panel hosting the retained llama-server web UI WebView. The WebView instance
 * outlives this composable (see ChatWebViewHolder), so backing out mid-generation doesn't cut
 * the reply off. imePadding keeps the web UI's input above the keyboard.
 */
@Composable
fun ChatPanel(state: MonitorUiState, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        PanelHeader("Chat", onBack)
        AndroidView(
            factory = { ctx -> ChatWebViewHolder.obtain(ctx, chatUrl(state.settings.port)) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}
