package com.iguar.armedllama.ui.menu

import com.iguar.armedllama.model.ServerSettings

/** The actions the menu sub-panels can invoke, forwarded to the ViewModel. */
data class MenuCallbacks(
    val onUpdateSettings: ((ServerSettings) -> ServerSettings) -> Unit,
    val onStartDeploy: () -> Unit,
    val onUpdateHfQuery: (String) -> Unit,
    val onDownloadModel: (String) -> Unit,
)
