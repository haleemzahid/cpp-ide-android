package dev.cppide.core.ai

/**
 * Lifecycle of a single downloadable model. The [ModelRepository] keeps
 * one of these per model id and emits updates via a cold [kotlinx.coroutines.flow.Flow].
 */
sealed interface ModelDownloadState {

    data object NotDownloaded : ModelDownloadState

    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : ModelDownloadState {
        /** 0f..1f, or 0f if [totalBytes] is unknown. */
        val progress: Float
            get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    data object Downloaded : ModelDownloadState

    data class Failed(val message: String) : ModelDownloadState
}
