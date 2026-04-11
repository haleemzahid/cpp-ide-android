package dev.cppide.core.ai

/**
 * Lifecycle of [AiEngine]. The engine owns a single hot [StateFlow] of
 * this type so any number of UI surfaces can reflect load/generation
 * progress without each one re-subscribing to lower-level events.
 *
 * Transitions are:
 *   Idle → Loading → Ready ⇄ Generating → Ready → Idle
 *     ↘  ↙
 *      Failed
 */
sealed interface AiEngineState {

    data object Idle : AiEngineState

    /** Engine is initializing — may take up to ~10s for big models. */
    data class Loading(val modelId: String) : AiEngineState

    /** Model is loaded and the engine is ready to receive prompts. */
    data class Ready(
        val modelId: String,
        val backend: AiBackend,
    ) : AiEngineState

    /** A prompt is currently streaming tokens. */
    data class Generating(
        val modelId: String,
        val backend: AiBackend,
    ) : AiEngineState

    /** Last transition failed. The engine is effectively [Idle] after this. */
    data class Failed(
        val modelId: String?,
        val message: String,
    ) : AiEngineState
}
