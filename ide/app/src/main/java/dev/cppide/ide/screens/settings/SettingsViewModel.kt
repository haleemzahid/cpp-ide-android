package dev.cppide.ide.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cppide.core.Core
import dev.cppide.ide.ai.ModelDownloadService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Holds [SettingsState] and translates [SettingsIntent]s into calls on
 * [Core.modelRepository]. Downloads outlive this VM — they run on the
 * repo's app-scoped CoroutineScope — so a configuration change or
 * navigation away from Settings doesn't cancel an in-flight transfer.
 */
class SettingsViewModel(
    private val core: Core,
    private val appContext: Context,
) : ViewModel() {

    private val repo = core.modelRepository

    private val _state = MutableStateFlow(
        SettingsState(
            models = repo.availableModels.map { info ->
                SettingsState.ModelRow(
                    info = info,
                    state = repo.allStates.value[info.id]
                        ?: dev.cppide.core.ai.ModelDownloadState.NotDownloaded,
                )
            }
        )
    )
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        // Mirror the repo's state map into our UI state whenever it changes.
        repo.allStates
            .onEach { snapshot ->
                _state.value = SettingsState(
                    models = repo.availableModels.map { info ->
                        SettingsState.ModelRow(
                            info = info,
                            state = snapshot[info.id]
                                ?: dev.cppide.core.ai.ModelDownloadState.NotDownloaded,
                        )
                    }
                )
            }
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.Download -> {
                // Kick the foreground service first so the OS accounts
                // for the ongoing network work before we begin downloading.
                ModelDownloadService.start(appContext)
                repo.startDownload(intent.modelId)
            }
            is SettingsIntent.Cancel -> repo.cancelDownload(intent.modelId)
            is SettingsIntent.Delete -> viewModelScope.launch {
                repo.delete(intent.modelId)
            }
        }
    }
}
