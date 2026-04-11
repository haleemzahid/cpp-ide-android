package dev.cppide.core.ai

import android.content.Context
import dev.cppide.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Default [ModelRepository] backed by `context.filesDir/models/`.
 *
 * Downloads run on an internal [CoroutineScope] so they outlive screen
 * navigation. Only one [Job] per model id is live at a time; the map is
 * the authority for "is this model currently downloading". Progress is
 * published via [states], throttled to one emit per [PROGRESS_EMIT_BYTES]
 * so low-end recomposition stays bounded.
 *
 * Downloads use plain [HttpURLConnection] — no OkHttp dependency — and
 * support HTTP Range resume. Partial downloads live next to the final
 * file as `<name>.part` and are atomically renamed on completion.
 */
class DefaultModelRepository(
    context: Context,
    private val dispatchers: DispatcherProvider,
    override val availableModels: List<ModelInfo> = ModelCatalog.DEFAULT,
) : ModelRepository {

    private val modelsDir: File = File(context.applicationContext.filesDir, "models").apply { mkdirs() }

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val jobs = mutableMapOf<String, Job>()
    private val jobsLock = Any()

    private val states = MutableStateFlow(initialStates())
    override val allStates: StateFlow<Map<String, ModelDownloadState>> = states.asStateFlow()

    private val _anyActive = MutableStateFlow(false)
    override val anyActive: StateFlow<Boolean> = _anyActive.asStateFlow()

    private fun initialStates(): Map<String, ModelDownloadState> =
        availableModels.associate { info ->
            info.id to if (finalFile(info).exists()) ModelDownloadState.Downloaded else ModelDownloadState.NotDownloaded
        }

    override fun downloadState(modelId: String): Flow<ModelDownloadState> =
        states
            .map { it[modelId] ?: ModelDownloadState.NotDownloaded }
            .distinctUntilChanged()

    override fun startDownload(modelId: String) {
        val info = availableModels.firstOrNull { it.id == modelId } ?: return

        synchronized(jobsLock) {
            if (jobs[modelId]?.isActive == true) return
            val current = states.value[modelId]
            if (current is ModelDownloadState.Downloaded) return

            val job = scope.launch { runDownload(info) }
            jobs[modelId] = job
            refreshAnyActive()
            job.invokeOnCompletion {
                synchronized(jobsLock) {
                    if (jobs[modelId] === job) jobs.remove(modelId)
                    refreshAnyActive()
                }
            }
        }
    }

    override fun cancelDownload(modelId: String) {
        val job = synchronized(jobsLock) { jobs.remove(modelId) }
        job?.cancel()
        // State rollback: if we were Downloading, drop back to NotDownloaded
        // so the UI doesn't show a stuck progress bar. The .part file stays.
        states.update { snapshot ->
            val cur = snapshot[modelId]
            if (cur is ModelDownloadState.Downloading) {
                snapshot + (modelId to ModelDownloadState.NotDownloaded)
            } else snapshot
        }
        refreshAnyActive()
    }

    override suspend fun delete(modelId: String) {
        cancelDownload(modelId)
        val info = availableModels.firstOrNull { it.id == modelId } ?: return
        finalFile(info).delete()
        File(modelsDir, "${info.fileName}.part").delete()
        publish(modelId, ModelDownloadState.NotDownloaded)
    }

    override suspend fun modelFile(modelId: String): File? {
        val info = availableModels.firstOrNull { it.id == modelId } ?: return null
        return finalFile(info).takeIf { it.exists() }
    }

    // ---------------------------------------------------------------- internals

    private suspend fun runDownload(info: ModelInfo) {
        val finalFile = finalFile(info)
        if (finalFile.exists()) {
            publish(info.id, ModelDownloadState.Downloaded)
            return
        }

        val partFile = File(modelsDir, "${info.fileName}.part")
        val existing = if (partFile.exists()) partFile.length() else 0L

        val conn = (URL(info.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            conn.connect()
            val code = conn.responseCode
            val resumed = code == HttpURLConnection.HTTP_PARTIAL
            val total: Long = when {
                resumed -> existing + conn.contentLengthLong
                code == HttpURLConnection.HTTP_OK ->
                    conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
                else -> error("HTTP $code while downloading ${info.id}")
            }

            // Server ignored Range — start fresh.
            if (!resumed && existing > 0) partFile.delete()
            val startFrom = if (resumed) existing else 0L

            FileOutputStream(partFile, /* append = */ resumed).use { out ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = startFrom
                    var lastEmitted = downloaded
                    publish(info.id, ModelDownloadState.Downloading(downloaded, total))

                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        downloaded += n
                        if (downloaded - lastEmitted >= PROGRESS_EMIT_BYTES) {
                            lastEmitted = downloaded
                            publish(info.id, ModelDownloadState.Downloading(downloaded, total))
                        }
                    }
                    out.fd.sync()
                }
            }

            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            publish(info.id, ModelDownloadState.Downloaded)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                // cancelDownload already rolled the state back; rethrow to
                // terminate the coroutine cleanly.
                throw t
            }
            publish(info.id, ModelDownloadState.Failed(t.message ?: t::class.java.simpleName))
        } finally {
            conn.disconnect()
        }
    }

    private fun finalFile(info: ModelInfo): File = File(modelsDir, info.fileName)

    private fun publish(id: String, state: ModelDownloadState) {
        states.update { it + (id to state) }
    }

    private fun refreshAnyActive() {
        _anyActive.value = synchronized(jobsLock) { jobs.values.any { it.isActive } }
    }

    private companion object {
        const val PROGRESS_EMIT_BYTES = 256L * 1024
    }
}
