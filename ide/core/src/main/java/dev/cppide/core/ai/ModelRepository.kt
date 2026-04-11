package dev.cppide.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Tracks which models are known to the IDE, which are on disk, and
 * drives a resumable download pipeline for the rest.
 *
 * The repository is the single source of truth for model file locations
 * and download state — no other layer should touch `filesDir/models/` directly.
 *
 * Downloads run on an internal, app-scoped coroutine so they survive
 * UI navigation. The caller kicks them off with [startDownload] and
 * observes progress via [downloadState] / [allStates]. To keep a
 * long-running download alive while the app is backgrounded, the host
 * app should start a foreground service that watches [anyActive] and
 * holds the process alive while downloads are in flight.
 */
interface ModelRepository {

    /** Static catalog of models the IDE can download. See [ModelCatalog]. */
    val availableModels: List<ModelInfo>

    /**
     * Hot map of every known model's current state. Emits on every
     * transition; suitable for the Settings screen and the download service.
     */
    val allStates: StateFlow<Map<String, ModelDownloadState>>

    /** Emits `true` whenever at least one model is in [ModelDownloadState.Downloading]. */
    val anyActive: StateFlow<Boolean>

    /**
     * Hot stream of the current state for [modelId]. Emits the latest
     * value on collect and every subsequent transition. Unknown ids
     * emit [ModelDownloadState.NotDownloaded].
     */
    fun downloadState(modelId: String): Flow<ModelDownloadState>

    /**
     * Start (or resume) downloading [modelId]. No-op if the model is
     * already downloaded or currently downloading. Downloads run on an
     * internal CoroutineScope so navigating away from the screen that
     * triggered the download does not cancel it.
     *
     * Safe to call after [ModelDownloadState.Failed] — picks up from
     * the existing `.part` file if the server supports HTTP Range.
     */
    fun startDownload(modelId: String)

    /**
     * Cancel an in-flight download for [modelId]. The partial `.part`
     * file is left on disk so the next [startDownload] resumes from it.
     * No-op if no download is active for [modelId].
     */
    fun cancelDownload(modelId: String)

    /**
     * Remove the downloaded file and any partial `.part` file for [modelId].
     * Cancels an active download first. No-op if nothing is on disk.
     */
    suspend fun delete(modelId: String)

    /**
     * Returns the on-disk [File] for a fully downloaded model, or null
     * if the model isn't downloaded yet. Does not trigger a download.
     */
    suspend fun modelFile(modelId: String): File?
}
