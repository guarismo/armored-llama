package com.iguar.armedllama.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.iguar.armedllama.MonitorViewModel
import com.iguar.armedllama.model.Panel
import com.iguar.armedllama.ui.components.TopBar
import com.iguar.armedllama.ui.console.ConsoleDashboard
import com.iguar.armedllama.ui.menu.MenuCallbacks
import com.iguar.armedllama.ui.menu.MenuOverlay
import com.iguar.armedllama.ui.theme.MonitorTheme

/**
 * Root screen: the **A · Console** dashboard with the shared top bar, plus the menu overlay
 * (drawer + Settings/Update/Hugging Face) layered on top.
 */
@Composable
fun MonitorScreen(vm: MonitorViewModel) {
    val state = vm.state
    val c = MonitorTheme.colors

    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize().background(c.bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            TopBar(
                running = state.running,
                host = state.host,
                modelFile = state.modelFile,
                onMenu = vm::openMenu,
                onToggle = vm::toggleRunning,
            )
            ConsoleDashboard(state = state, modifier = Modifier)
        }

        MenuOverlay(
            state = state,
            onDismiss = vm::closeMenu,
            onNavigate = vm::navigate,
            onBack = vm::backToMenu,
            callbacks = MenuCallbacks(
                onUpdateSettings = vm::updateSettings,
                onStartDeploy = vm::startDeploy,
                onUpdateHfQuery = vm::updateHfQuery,
                onDownloadModel = vm::downloadModel,
            ),
        )
    }
}
