package dev.cppide.core.ai

/**
 * Hardware backend request for [AiEngine].
 *
 * [Auto] is the right default on heterogeneous Android fleets — the
 * engine tries GPU first and silently falls back to CPU if OpenCL or
 * the VNDK shim isn't available on the device. Use [Cpu] or [Gpu]
 * explicitly only when the caller has specific device knowledge.
 */
sealed interface AiBackend {
    data object Auto : AiBackend
    data object Cpu : AiBackend
    data object Gpu : AiBackend
}
