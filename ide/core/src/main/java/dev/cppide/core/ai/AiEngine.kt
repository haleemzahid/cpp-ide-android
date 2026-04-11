package dev.cppide.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * High-level facade over the on-device LLM runtime.
 *
 * Keeps [com.google.ai.edge.litertlm] types out of the rest of the
 * codebase so the underlying engine can be swapped or mocked without
 * touching ViewModels. Implementations must be safe to call from any
 * coroutine — internally they serialize load/generate/unload onto a
 * single inference thread because LiteRT-LM's `Engine` is not
 * thread-safe.
 */
interface AiEngine {

    /** Hot stream of the current engine lifecycle state. */
    val state: StateFlow<AiEngineState>

    /**
     * Load [modelFile] into the engine, replacing any previously loaded
     * model. Suspends for up to ~10s on big models; callers should show
     * a progress spinner from [state].
     *
     * If [backend] is [AiBackend.Auto], the implementation tries GPU
     * first and falls back to CPU on failure so one API call works
     * across the device fleet.
     *
     * Throws on failure and transitions [state] to [AiEngineState.Failed].
     */
    suspend fun load(
        modelFile: File,
        modelId: String,
        backend: AiBackend = AiBackend.Auto,
    )

    /**
     * Start generating a response to [prompt]. Returns a cold flow that,
     * when collected, emits decoded tokens as they're produced. Cancel
     * the collection to stop generation early.
     *
     * Must only be called when [state] is [AiEngineState.Ready].
     * Behavior when called in any other state is implementation-defined;
     * the default implementation throws.
     */
    fun generate(prompt: String): Flow<String>

    /**
     * Release the currently loaded model and return to [AiEngineState.Idle].
     * Safe to call repeatedly or when already idle.
     */
    suspend fun unload()
}
