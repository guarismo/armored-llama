package com.iguar.armoredllama

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.iguar.armoredllama.model.Panel
import com.iguar.armoredllama.ui.MonitorScreen
import com.iguar.armoredllama.ui.chat.ChatWebViewHolder
import com.iguar.armoredllama.ui.theme.ArmoredLlamaTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MonitorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Hardware back closes any open menu surface before leaving the app.
        onBackPressedDispatcher.addCallback(this) {
            when (viewModel.state.panel) {
                Panel.MENU -> viewModel.closeMenu()
                Panel.CHAT, Panel.SETTINGS, Panel.RELEASE, Panel.HF -> viewModel.backToMenu()
                null -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        }
        setContent {
            ArmoredLlamaTheme {
                MonitorScreen(viewModel)
            }
        }
    }

    override fun onDestroy() {
        ChatWebViewHolder.destroy()
        super.onDestroy()
    }
}
