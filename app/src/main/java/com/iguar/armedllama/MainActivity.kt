package com.iguar.armedllama

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.iguar.armedllama.model.Panel
import com.iguar.armedllama.ui.MonitorScreen
import com.iguar.armedllama.ui.theme.ArmedLlamaTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MonitorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Hardware back closes any open menu surface before leaving the app.
        onBackPressedDispatcher.addCallback(this) {
            when (viewModel.state.panel) {
                Panel.MENU -> viewModel.closeMenu()
                Panel.SETTINGS, Panel.RELEASE, Panel.HF -> viewModel.backToMenu()
                null -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        }
        setContent {
            ArmedLlamaTheme {
                MonitorScreen(viewModel)
            }
        }
    }
}
