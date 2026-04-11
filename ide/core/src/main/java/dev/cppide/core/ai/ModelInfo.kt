package dev.cppide.core.ai

/**
 * Metadata describing a single downloadable GGUF model.
 *
 * [sizeBytes] is a best-effort estimate used for UI until the HTTP
 * Content-Length is known — the download service always trusts the
 * server's reported size over this field at runtime.
 */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String? = null,
)
