package dev.cppide.ide.screens.chat

import dev.cppide.core.ai.AiEngineState
import dev.cppide.core.ai.ModelInfo

/**
 * Immutable UI state for the Chat screen. Rebuilt by [ChatViewModel]
 * whenever the engine state, the set of downloaded models, or the
 * conversation itself changes.
 */
data class ChatState(
    /** Engine lifecycle mirrored from `core.aiEngine.state`. */
    val engine: AiEngineState = AiEngineState.Idle,

    /** Models the user has actually downloaded (subset of the catalog). */
    val downloadedModels: List<ModelInfo> = emptyList(),

    /** Currently selected model id, or null if none picked yet. */
    val selectedModelId: String? = null,

    /** Text currently in the input box. */
    val inputText: String = "",

    /** Ordered list of chat turns. The last turn may be streaming. */
    val messages: List<ChatMessage> = emptyList(),
)

/**
 * Single chat turn. [streaming] is true for an assistant message that's
 * still receiving tokens — the UI uses this to show a spinner/caret.
 */
data class ChatMessage(
    val role: Role,
    val text: String,
    val streaming: Boolean = false,
) {
    enum class Role { User, Assistant }
}
