package dev.cppide.ide.screens.editor

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cppide.core.Core
import dev.cppide.core.build.BuildConfig
import dev.cppide.core.build.BuildResult
import dev.cppide.core.build.Diagnostic
import dev.cppide.core.lsp.LspCompletion
import dev.cppide.core.project.Project
import dev.cppide.core.run.RunConfig
import dev.cppide.core.run.RunEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Holds [EditorState] and processes [EditorIntent]s. Owns the long-running
 * build+run job so it can be cancelled by the user (Stop button).
 *
 * All I/O goes through [Core] services — no Android-specific APIs leak
 * in here, so the VM can be unit-tested with a fake [Core].
 */
class EditorViewModel(
    private val core: Core,
    project: Project,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorState(project = project))
    val state: StateFlow<EditorState> = _state.asStateFlow()

    /** Active build/run task. Cancelled by [stop] or by starting a new run. */
    private var runJob: Job? = null

    /** Debounced auto-save job. Reset on every keystroke. */
    private var autoSaveJob: Job? = null

    /** Debounced LSP didChange job. Sent ~200ms after the last keystroke. */
    private var lspChangeJob: Job? = null

    /** Monotonic LSP document version, increments on every didChange. */
    private val lspVersion = AtomicInteger(1)

    init {
        viewModelScope.launch {
            core.projectService.open(project.root, project.name)
                .onSuccess { _ ->
                    _state.update { it.copy(fileTree = core.projectService.fileTree.value) }
                    val mainCpp = core.projectService.fileTree.value?.children
                        ?.firstOrNull { node -> node.name == "main.cpp" }
                    if (mainCpp != null) handle(EditorIntent.OpenFile(mainCpp.relativePath))
                }
                .onFailure { t -> _state.update { it.copy(errorMessage = t.message) } }
        }

        // Mirror clangd state + diagnostics into our screen state.
        viewModelScope.launch {
            core.lspService.state.collect { lsp ->
                val previous = _state.value.lspState
                _state.update { it.copy(lspState = lsp) }
                // Replay didOpen for the active file when clangd transitions
                // to Ready. The init block opens the file in parallel with
                // starting clangd, so the first didOpen often arrives while
                // server == null and is silently dropped. Resending here
                // ensures clangd has the document state and starts producing
                // diagnostics + completions for it.
                if (lsp is dev.cppide.core.lsp.LspState.Ready &&
                    previous !is dev.cppide.core.lsp.LspState.Ready) {
                    _state.value.openFile?.let { f ->
                        val file = core.projectService.resolve(f.relativePath)
                        val lang = if (file.extension.lowercase() == "c") "c" else "cpp"
                        core.lspService.didOpen(file, lang, f.content)
                    }
                }
            }
        }
        viewModelScope.launch {
            core.lspService.diagnostics.collect { byFile ->
                _state.update { it.copy(lspDiagnosticsByFile = byFile) }
            }
        }

        // Start clangd asynchronously. The toolchain may need to install
        // first; that's a one-time ~30s operation. The editor stays
        // usable in the meantime — completions/diagnostics just arrive
        // later when LSP transitions to Ready.
        viewModelScope.launch {
            ensureLspStarted(project)
        }
    }

    private suspend fun ensureLspStarted(project: Project) {
        // Always call install() — it's idempotent and ensures bin/ symlinks
        // are valid. We can't trust isReady() alone: a previous install
        // might have run before we added new aliases (e.g. the clangd
        // symlink), so the marker says "ready" but the new symlink is
        // missing. install() re-validates every time.
        val r = core.toolchain.install()
        if (r.isSuccess) {
            core.lspService.start(project)
        }
    }

    fun handle(intent: EditorIntent) {
        when (intent) {
            // ---- file system ----
            EditorIntent.ToggleDrawer -> _state.update { it.copy(drawerOpen = !it.drawerOpen) }
            EditorIntent.CloseDrawer -> _state.update { it.copy(drawerOpen = false) }
            is EditorIntent.OpenFile -> openFile(intent.relativePath)
            is EditorIntent.EditContent -> {
                _state.update { s ->
                    s.copy(openFile = s.openFile?.copy(content = intent.newContent))
                }
                scheduleAutoSave()
                scheduleLspDidChange(intent.newContent)
            }
            EditorIntent.Save -> viewModelScope.launch { save() }

            // ---- build / run ----
            EditorIntent.RunOrStop -> if (_state.value.isBusy) stop() else runCurrent()
            EditorIntent.ToggleBottomPanel -> _state.update { it.copy(bottomPanelVisible = !it.bottomPanelVisible) }
            is EditorIntent.SwitchBottomTab -> _state.update { it.copy(bottomPanelTab = intent.tab, bottomPanelVisible = true) }
            is EditorIntent.JumpToDiagnostic -> jumpTo(intent.diagnostic)
            EditorIntent.ClearTerminal -> _state.update { it.copy(terminalLines = emptyList()) }

            // ---- misc ----
            EditorIntent.DismissError -> _state.update { it.copy(errorMessage = null) }
        }
    }

    // ---- file ops ----

    private fun openFile(relativePath: String) {
        viewModelScope.launch {
            core.projectService.read(relativePath)
                .onSuccess { content ->
                    _state.update { s ->
                        s.copy(
                            openFile = OpenFile(relativePath, content, content),
                            drawerOpen = false,
                        )
                    }
                    // Tell clangd we opened the file. Language id by extension.
                    val file = core.projectService.resolve(relativePath)
                    val lang = if (file.extension.lowercase() == "c") "c" else "cpp"
                    core.lspService.didOpen(file, lang, content)
                }
                .onFailure { t -> _state.update { it.copy(errorMessage = "open: ${t.message}") } }
        }
    }

    /**
     * Debounced LSP didChange. Each keystroke schedules a notification
     * a short time in the future; the next keystroke cancels the pending
     * one so clangd doesn't get hammered while the user is typing fast.
     */
    private fun scheduleLspDidChange(newContent: String) {
        val openFile = _state.value.openFile ?: return
        lspChangeJob?.cancel()
        lspChangeJob = viewModelScope.launch {
            delay(LSP_CHANGE_DEBOUNCE_MS)
            val file = core.projectService.resolve(openFile.relativePath)
            core.lspService.didChange(file, newContent, lspVersion.incrementAndGet())
        }
    }

    /**
     * Debounced auto-save. Each call cancels the previous pending save and
     * schedules a new one [AUTO_SAVE_DELAY_MS] in the future. The user
     * never has to tap the save button — they just type, pause for a
     * second, and it's persisted.
     */
    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(AUTO_SAVE_DELAY_MS)
            save()
        }
    }

    private suspend fun save(): Boolean {
        val openFile = _state.value.openFile ?: return true
        if (!openFile.isDirty) return true
        _state.update { it.copy(saving = true) }
        return core.projectService.write(openFile.relativePath, openFile.content)
            .fold(
                onSuccess = {
                    _state.update { s ->
                        s.copy(
                            openFile = openFile.copy(savedContent = openFile.content),
                            saving = false,
                            fileTree = core.projectService.fileTree.value,
                        )
                    }
                    val file = core.projectService.resolve(openFile.relativePath)
                    core.lspService.didSave(file)
                    true
                },
                onFailure = { t ->
                    _state.update { it.copy(saving = false, errorMessage = "save: ${t.message}") }
                    false
                },
            )
    }

    // ---- build / run pipeline ----

    private fun runCurrent() {
        runJob?.cancel()
        runJob = viewModelScope.launch {
            val openFile = _state.value.openFile ?: run {
                appendInfo("No file open"); return@launch
            }

            // 0. Auto-save
            if (!save()) return@launch

            // 1. Always run install() — it's idempotent. Critical for symlink
            //    repair: nativeLibraryDir gets a fresh random path on every
            //    APK reinstall, so any old symlinks in filesDir/termux/bin/
            //    point at a path that no longer exists. install() always
            //    re-validates them via ensureSymlinks(), even when the marker
            //    says "already extracted".
            val needsExtract = !core.toolchain.isReady()
            if (needsExtract) {
                _state.update { it.copy(
                    runState = RunState.InstallingToolchain,
                    bottomPanelVisible = true,
                    bottomPanelTab = BottomPanelTab.Terminal,
                ) }
                appendInfo("Installing toolchain (first run, ~30s)…")
            }
            val install = core.toolchain.install { progress ->
                if (needsExtract) appendInfo("  $progress")
            }
            if (install.isFailure) {
                appendError("Toolchain install failed: ${install.exceptionOrNull()?.message}")
                _state.update { it.copy(runState = RunState.Idle) }
                return@launch
            }

            // 2. Build
            _state.update { it.copy(
                runState = RunState.Building,
                bottomPanelVisible = true,
                bottomPanelTab = BottomPanelTab.Terminal,
                problems = emptyList(),
            ) }
            appendInfo("Building ${openFile.name}…")

            val source = core.projectService.resolve(openFile.relativePath)
            val buildDir = File(core.context.filesDir, "build/${state.value.project.name}")
            buildDir.mkdirs()
            val outputSo = File(buildDir, "libuser.so")

            val config = BuildConfig(
                projectRoot = state.value.project.root,
                sources = listOf(source),
                output = outputSo,
                targetApi = 26,
                runtimeShim = core.runtimeShimSource(),
            )

            val buildResult = core.buildService.build(config)
            when (buildResult) {
                is BuildResult.Success -> {
                    appendInfo("Build OK in ${buildResult.durationMs} ms")
                    _state.update { it.copy(problems = buildResult.diagnostics) }
                    runProgram(buildResult.artifact)
                }
                is BuildResult.Failure -> {
                    appendError("Build failed (exit ${buildResult.exitCode}, ${buildResult.diagnostics.count { it.isError }} errors)")
                    _state.update { it.copy(
                        runState = RunState.Idle,
                        problems = buildResult.diagnostics,
                        bottomPanelTab = if (buildResult.diagnostics.isNotEmpty())
                            BottomPanelTab.Problems else BottomPanelTab.Terminal,
                    ) }
                }
                is BuildResult.Error -> {
                    appendError("Build error: ${buildResult.message}")
                    _state.update { it.copy(runState = RunState.Idle) }
                }
            }
        }
    }

    private suspend fun runProgram(library: File) {
        _state.update { it.copy(runState = RunState.Running, bottomPanelTab = BottomPanelTab.Terminal) }

        core.runService.run(RunConfig(library = library)).collect { event ->
            when (event) {
                RunEvent.Started -> appendInfo("Running…")
                is RunEvent.Stdout -> appendStdout(event.text)
                is RunEvent.Stderr -> appendStderr(event.text)
                is RunEvent.Exited -> {
                    appendInfo("Process exited ${event.exitCode} (${event.durationMs} ms)")
                    _state.update { it.copy(runState = RunState.Idle) }
                }
                is RunEvent.Failed -> {
                    appendError("Run failed: ${event.message}")
                    _state.update { it.copy(runState = RunState.Idle) }
                }
            }
        }
    }

    private fun stop() {
        runJob?.cancel()
        runJob = null
        appendInfo("Stopped")
        _state.update { it.copy(runState = RunState.Idle) }
    }

    /**
     * Fetch clangd completions for the open file at [line]/[column]
     * (both 0-indexed). Called by the editor's completion worker thread
     * via a `runBlocking` bridge in [LspCppLanguage], so this runs off
     * the main thread.
     *
     * [liveContent] is the editor's live text pulled straight from
     * sora's [io.github.rosemoe.sora.text.Content] — NOT our Compose
     * state copy. The ContentChangeEvent → state update chain has
     * latency, so `_state.value.openFile.content` may lag by a keystroke
     * or two; using the live text guarantees clangd sees exactly what
     * the user sees.
     *
     * We also cancel any pending debounced didChange and send a fresh
     * one synchronously, otherwise clangd answers on a stale buffer.
     */
    suspend fun requestCompletion(
        liveContent: String,
        line: Int,
        column: Int,
    ): List<LspCompletion> {
        val openFile = _state.value.openFile ?: return emptyList()
        val file = core.projectService.resolve(openFile.relativePath)
        lspChangeJob?.cancelAndJoin()
        lspChangeJob = null
        val version = lspVersion.incrementAndGet()
        Log.d(
            TAG,
            "requestCompletion flush didChange v=$version len=${liveContent.length} " +
                "pos=$line:$column file=${file.name}"
        )
        core.lspService.didChange(file, liveContent, version)
        val items = core.lspService.complete(file, line, column)
        Log.d(TAG, "requestCompletion got ${items.size} items")
        return items
    }

    /**
     * Fetch clangd hover info for the open file at [line]/[column] (both
     * 0-indexed). Invoked by [EditorPane] on long-press. Returns null
     * when clangd has nothing — caller suppresses the tooltip then.
     */
    suspend fun requestHover(line: Int, column: Int): String? {
        val openFile = _state.value.openFile ?: return null
        val file = core.projectService.resolve(openFile.relativePath)
        return core.lspService.hover(file, line, column)
    }

    // ---- diagnostic helpers ----

    private fun jumpTo(diagnostic: Diagnostic) {
        // Jump to the file (the editor pane scroll-to-line is a v2 polish item)
        val rel = diagnostic.file.removePrefix(state.value.project.root.absolutePath).trimStart('/')
        if (rel.isNotEmpty() && rel != state.value.openFile?.relativePath) {
            handle(EditorIntent.OpenFile(rel))
        }
    }

    // ---- terminal append helpers ----

    private fun appendStdout(text: String) = _state.update {
        it.copy(terminalLines = it.terminalLines + TerminalLine.Stdout(text))
    }
    private fun appendStderr(text: String) = _state.update {
        it.copy(terminalLines = it.terminalLines + TerminalLine.Stderr(text))
    }
    private fun appendInfo(text: String) = _state.update {
        it.copy(terminalLines = it.terminalLines + TerminalLine.Info(text))
    }
    private fun appendError(text: String) = _state.update {
        it.copy(terminalLines = it.terminalLines + TerminalLine.Error(text))
    }

    override fun onCleared() {
        super.onCleared()
        // Stop clangd when the screen is destroyed; releases ~200MB of
        // process RAM and lets a fresh project start cleanly.
        viewModelScope.launch { core.lspService.stop() }
    }

    companion object {
        private const val TAG = "cppide-editor"
        /** How long after the last keystroke to auto-save. */
        private const val AUTO_SAVE_DELAY_MS = 1_000L
        /** How long after the last keystroke to push didChange to clangd. */
        private const val LSP_CHANGE_DEBOUNCE_MS = 200L
    }
}
