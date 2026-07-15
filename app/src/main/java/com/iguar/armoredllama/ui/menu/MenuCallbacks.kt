package com.iguar.armoredllama.ui.menu

import com.iguar.armoredllama.model.ServerSettings
import com.iguar.armoredllama.server.CompanionKind

/** The actions the menu sub-panels can invoke, forwarded to the ViewModel. */
data class MenuCallbacks(
    val onUpdateSettings: ((ServerSettings) -> ServerSettings) -> Unit,
    val onCheckUpdate: () -> Unit,
    val onDownloadUpdate: () -> Unit,
    val onRemoveDownloadedUpdate: () -> Unit,
    val onUpdateHfQuery: (String) -> Unit,
    val onDownloadModel: (String, String) -> Unit,
    val onDownloadCompanion: (String, String, CompanionKind) -> Unit,
    val onSwitchModel: (String) -> Unit,
    val onDeleteModel: (String) -> Unit,
)
