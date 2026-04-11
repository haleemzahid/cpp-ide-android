package dev.cppide.core.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import dev.cppide.core.common.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [AiEngine] backed by Google's LiteRT-LM Android runtime
 * (`com.google.ai.edge.litertlm:litertlm-android`).
 *
 * ### Threading
 * LiteRT-LM's [Engine] is not thread-safe, so every call that touches
 * it is funnelled through a single-parallelism dispatcher plus a
 * [Mutex]. The mutex serializes load/unload against generation, while
 * the dispatcher guarantees that the underlying native state is only
 * mutated from one OS thread at a time.
 *
 * ### Backend auto-selection
 * When the caller requests [AiBackend.Auto] we attempt [Backend.GPU]
 * first and fall back to [Backend.CPU] on init failure. This works
 * because LiteRT-LM throws synchronously during [Engine.initialize]
 * on devices without OpenCL / VNDK — one API call, two-device support.
 *
 * ### Cancellation
 * Generation is a cold [Flow]; cancelling its collection cancels the
 * underlying `sendMessageAsync` stream. The engine stays loaded for
 * the next prompt.
 */
class LiteRtAiEngine(
    dispatchers: DispatcherProvider,
    private val cacheDir: File,
) : AiEngine {

    private val _state = MutableStateFlow<AiEngineState>(AiEngineState.Idle)
    override val state: StateFlow<AiEngineState> = _state.asStateFlow()

    // One-at-a-time execution on top of Dispatchers.IO. Cheaper than
    // newSingleThreadContext and doesn't require DelicateCoroutinesApi.
    private val inferenceDispatcher = dispatchers.io.limitedParallelism(1)
    private val mutex = Mutex()

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var loaded: Loaded? = null

    private data class Loaded(val modelId: String, val backend: AiBackend)

    override suspend fun load(
        modelFile: File,
        modelId: String,
        backend: AiBackend,
    ) = mutex.withLock {
        withContext(inferenceDispatcher) {
            unloadInternal()
            _state.value = AiEngineState.Loading(modelId)
            cacheDir.mkdirs()

            val attempts = when (backend) {
                AiBackend.Auto -> listOf(AiBackend.Gpu, AiBackend.Cpu)
                AiBackend.Gpu -> listOf(AiBackend.Gpu)
                AiBackend.Cpu -> listOf(AiBackend.Cpu)
            }

            var lastError: Throwable? = null
            for (attempt in attempts) {
                try {
                    val cfg = EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = attempt.toLiteRtBackend(),
                        cacheDir = cacheDir.absolutePath,
                    )
                    val eng = Engine(cfg)
                    eng.initialize()
                    val convo = eng.createConversation()
                    engine = eng
                    conversation = convo
                    loaded = Loaded(modelId, attempt)
                    _state.value = AiEngineState.Ready(modelId, attempt)
                    return@withContext
                } catch (t: Throwable) {
                    lastError = t
                    // Next attempt — the partially-constructed engine
                    // is released below before we retry.
                    safeClose()
                }
            }

            val err = lastError ?: IllegalStateException("No backend attempts for $backend")
            _state.value = AiEngineState.Failed(modelId, err.message ?: err::class.java.simpleName)
            throw err
        }
    }

    override fun generate(prompt: String): Flow<String> = flow {
        val convo = conversation ?: error("AiEngine not loaded — call load() first")
        val current = loaded ?: error("AiEngine not loaded — call load() first")
        _state.value = AiEngineState.Generating(current.modelId, current.backend)
        convo.sendMessageAsync(prompt).collect { token ->
            emit(token.toString())
        }
    }
        .flowOn(inferenceDispatcher)
        .onCompletion {
            // Restore Ready state regardless of success/cancellation/error,
            // as long as the engine is still loaded.
            val current = loaded
            if (current != null) {
                _state.value = AiEngineState.Ready(current.modelId, current.backend)
            }
        }

    override suspend fun unload() = mutex.withLock {
        withContext(inferenceDispatcher) {
            unloadInternal()
        }
    }

    // ---------------------------------------------------------------- internals

    private fun unloadInternal() {
        safeClose()
        loaded = null
        _state.value = AiEngineState.Idle
    }

    /** Close native resources, swallowing exceptions so a bad close
     *  doesn't mask the underlying cause in the caller's stack. */
    private fun safeClose() {
        try { conversation?.close() } catch (_: Throwable) {}
        try { engine?.close() } catch (_: Throwable) {}
        conversation = null
        engine = null
    }

    private fun AiBackend.toLiteRtBackend(): Backend = when (this) {
        AiBackend.Gpu -> Backend.GPU()
        AiBackend.Cpu -> Backend.CPU()
        AiBackend.Auto -> Backend.CPU() // unreachable — Auto is expanded above
    }
}
