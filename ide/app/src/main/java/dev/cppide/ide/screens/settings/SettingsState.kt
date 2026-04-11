package dev.cppide.ide.screens.settings

import dev.cppide.core.ai.ModelDownloadState
import dev.cppide.core.ai.ModelInfo

/**
 * Immutable UI state for the Settings screen. The ViewModel rebuilds this
 * whenever the underlying repository emits new download states.
 */
data class SettingsState(
    val models: List<ModelRow> = emptyList(),
) {
    data class ModelRow(
        val info: ModelInfo,
        val state: ModelDownloadState,
    )
}
