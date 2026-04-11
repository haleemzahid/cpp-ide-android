package dev.cppide.ide.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cppide.core.Core
import dev.cppide.core.ai.AiEngineState
import dev.cppide.core.ai.ModelDownloadState
import dev.cppide.core.ai.ModelInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds [ChatState] and bridges it to `core.aiEngine` + `core.modelRepository`.
 *
 * Responsibilities:
 *  - Track which models are actually on disk (not just in the catalog)
 *  - Load the selected model into the engine on demand (first Send after a
 *    fresh model selection); keep it loaded across subsequent Sends
 *  - Stream tokens from `aiEngine.generate()` into the last assistant message
 *  - Cancel the stream cleanly on Stop
 *  - Mirror the engine's own [AiEngineState] into UI state
 */
class ChatViewModel(
    private val core: Core,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    /** Active generation job, cancelled by [ChatIntent.Stop] or a new [ChatIntent.Send]. */
    private var generateJob: Job? = null

    init {
        // Mirror engine state.
        core.aiEngine.state
            .onEach { engine -> _state.update { it.copy(engine = engine) } }
            .launchIn(viewModelScope)

        // Mirror download states → "downloaded models" list.
        core.modelRepository.allStates
            .onEach { snapshot ->
                val downloaded = core.modelRepository.availableModels.filter { info ->
                    snapshot[info.id] is ModelDownloadState.Downloaded
                }
                _state.update {
                    it.copy(
                        downloadedModels = downloaded,
                        // Auto-pick the first downloaded model if none selected yet.
                        selectedModelId = it.selectedModelId ?: downloaded.firstOrNull()?.id,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.UpdateInput -> _state.update { it.copy(inputText = intent.text) }

            is ChatIntent.SelectModel -> {
                // Switching model unloads the engine so the next Send picks
                // up the new weights. The actual load happens lazily.
                if (_state.value.selectedModelId != intent.modelId) {
                    viewModelScope.launch { core.aiEngine.unload() }
                    _state.update { it.copy(selectedModelId = intent.modelId) }
                }
            }

            ChatIntent.Send -> send()

            ChatIntent.Stop -> {
                generateJob?.cancel()
                generateJob = null
                // Seal any streaming message so it stops showing the caret.
                _state.update { current ->
                    val sealed = current.messages.map { it.copy(streaming = false) }
                    current.copy(messages = sealed)
                }
            }

            ChatIntent.Reset -> {
                generateJob?.cancel()
                generateJob = null
                _state.update { it.copy(messages = emptyList(), inputText = "") }
            }
        }
    }

    private fun send() {
        val snapshot = _state.value
        val text = snapshot.inputText.trim()
        val modelId = snapshot.selectedModelId ?: return
        if (text.isEmpty()) return

        val model = snapshot.downloadedModels.firstOrNull { it.id == modelId } ?: return

        // Append the user message + an empty streaming assistant message
        // that we'll fill in as tokens arrive.
        _state.update { current ->
            current.copy(
                inputText = "",
                messages = current.messages +
                    ChatMessage(ChatMessage.Role.User, text) +
                    ChatMessage(ChatMessage.Role.Assistant, "", streaming = true),
            )
        }

        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            try {
                ensureLoaded(model)
                core.aiEngine.generate(text).collect { token ->
                    _state.update { current ->
                        val last = current.messages.lastOrNull() ?: return@update current
                        val updated = current.messages.toMutableList().apply {
                            this[lastIndex] = last.copy(text = last.text + token)
                        }
                        current.copy(messages = updated)
                    }
                }
            } catch (t: Throwable) {
                // Replace the (likely empty) streaming bubble with an error line.
                _state.update { current ->
                    val msgs = current.messages.toMutableList()
                    val lastIdx = msgs.lastIndex
                    if (lastIdx >= 0 && msgs[lastIdx].role == ChatMessage.Role.Assistant) {
                        msgs[lastIdx] = msgs[lastIdx].copy(
                            text = "⚠️ ${t.message ?: t::class.java.simpleName}",
                            streaming = false,
                        )
                    }
                    current.copy(messages = msgs)
                }
            } finally {
                _state.update { current ->
                    current.copy(
                        messages = current.messages.map { it.copy(streaming = false) },
                    )
                }
            }
        }
    }

    /**
     * Ensure the engine has [model] loaded. No-op if already loaded; otherwise
     * calls `core.aiEngine.load()`. Suspends until ready — callers must handle
     * the ~10s latency in their own progress UI via [state].engine.
     */
    private suspend fun ensureLoaded(model: ModelInfo) {
        val current = core.aiEngine.state.value
        val alreadyLoaded = current is AiEngineState.Ready && current.modelId == model.id ||
            current is AiEngineState.Generating && current.modelId == model.id
        if (alreadyLoaded) return

        val file = core.modelRepository.modelFile(model.id)
            ?: error("Model ${model.id} is not downloaded")
        core.aiEngine.load(modelFile = file, modelId = model.id)
    }

    override fun onCleared() {
        super.onCleared()
        generateJob?.cancel()
        // Engine stays loaded — it's a process-singleton owned by Core,
        // so the next time the user opens Chat we skip the 10s init.
    }
}
