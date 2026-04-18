package dev.cppide.ide.screens.editor

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cppide.core.Core
import dev.cppide.core.build.BuildConfig
import dev.cppide.core.build.BuildResult
import dev.cppide.core.build.Diagnostic
import dev.cppide.core.debug.DebuggerState
import dev.cppide.core.lsp.LspCompletion
import dev.cppide.core.project.Project
import dev.cppide.core.run.RunConfig
import dev.cppide.core.run.RunEvent
import dev.cppide.ide.util.slugToTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val project: Project,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorState(project = project))
    val state: StateFlow<EditorState> = _state.asStateFlow()

    /** One-shot UI events that can't be represented as state (share sheet,
     *  snackbars). Collected once in the screen's LaunchedEffect. */
    private val _events = MutableSharedFlow<EditorEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<EditorEvent> = _events.asSharedFlow()

    /** Active build/run task. Cancelled by [stop] or by starting a new run. */
    private var runJob: Job? = null

    /** Active debug-start task. Separate from runJob so a debug launch can
     *  coexist with a non-debug run cleanup. */
    private var debugJob: Job? = null

    /**
     * Stdin channel for the currently running inferior. UI typing lands
     * here; [DefaultRunService] collects from it and pipes bytes to the
     * child's stdin. `extraBufferCapacity` is modest because the user
     * can't type fast enough to overflow a 64-entry buffer; `replay=0`
     * because early chunks shouldn't be delivered to the NEXT run.
     */
    private val terminalStdin = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
    )

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
                    val tree = core.projectService.fileTree.value
                    _state.update { it.copy(fileTree = tree) }
                    restoreOrSeedTabs(tree)
                    _state.update { it.copy(projectLoading = false) }
                }
                .onFailure { t ->
                    _state.update { it.copy(errorMessage = t.message, projectLoading = false) }
                }
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

        // Mirror debugger state into editor state so the UI can render
        // the control bar without going through Core directly.
        viewModelScope.launch {
            core.debuggerService.state.collect { dbg ->
                _state.update { it.copy(debuggerState = dbg) }
                when (dbg) {
                    is DebuggerState.Stopped -> {
                        val file = dbg.sourceFile
                        if (file != null && dbg.sourceLine != null) {
                            autoOpenStoppedFile(file)
                        }
                        refreshVariablesForTopFrame(dbg)
                    }
                    is DebuggerState.Running -> {
                        _state.update {
                            it.copy(
                                debugScopes = emptyList(),
                                debugVariables = emptyMap(),
                                expandedVariableRefs = emptySet(),
                            )
                        }
                    }
                    is DebuggerState.Exited ->
                        appendInfo("■ debug session exited (code=${dbg.code}${if (dbg.signaled) ", signaled" else ""})")
                    is DebuggerState.Failed ->
                        appendError("debug failed: ${dbg.message}")
                    is DebuggerState.Starting,
                    DebuggerState.Idle -> Unit
                }
            }
        }
        // Pipe inferior stdout/stderr from lldb-server's O packets into
        // the terminal view alongside the normal run output.
        viewModelScope.launch {
            core.debuggerService.output.collect { line -> appendStdout(line) }
        }
        // Mirror the debugger's breakpoint table into screen state so
        // the gutter can render red dots for set lines.
        viewModelScope.launch {
            core.debuggerService.breakpoints.collect { map ->
                _state.update { it.copy(breakpoints = map) }
            }
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
            is EditorIntent.CloseTab -> closeTab(intent.index)
            is EditorIntent.SelectTab -> selectTab(intent.index)
            is EditorIntent.EditContent -> {
                _state.update { s ->
                    val i = s.activeTabIndex ?: return@update s
                    val updated = s.openTabs.toMutableList().also { list ->
                        list[i] = list[i].copy(content = intent.newContent)
                    }
                    s.copy(openTabs = updated)
                }
                scheduleAutoSave()
                scheduleLspDidChange(intent.newContent)
            }
            EditorIntent.Save -> viewModelScope.launch { save() }
            EditorIntent.ShareActiveFile -> shareActiveFile()
            EditorIntent.ToggleMarkdownPreview ->
                _state.update { it.copy(markdownPreview = !it.markdownPreview) }
            is EditorIntent.CreateFile -> createFile(intent.parentRelativePath, intent.name)
            is EditorIntent.CreateDirectory -> createDirectory(intent.parentRelativePath, intent.name)
            is EditorIntent.DeleteFile -> deleteFile(intent.relativePath)
            is EditorIntent.RenameFile -> renameFile(intent.relativePath, intent.newName)

            // ---- build / run ----
            EditorIntent.RunOrStop -> if (_state.value.isBusy) stop() else runCurrent()
            EditorIntent.ToggleBottomPanel -> _state.update { it.copy(bottomPanelVisible = !it.bottomPanelVisible) }
            is EditorIntent.SwitchBottomTab -> {
                val s = _state.value
                if (s.bottomPanelTab != intent.tab || !s.bottomPanelVisible) {
                    _state.update { it.copy(bottomPanelTab = intent.tab, bottomPanelVisible = true) }
                }
            }
            is EditorIntent.JumpToDiagnostic -> jumpTo(intent.diagnostic)
            EditorIntent.ClearTerminal -> _state.update { it.copy(terminalLines = emptyList()) }
            is EditorIntent.SendTerminalInput -> sendTerminalInput(intent.text)

            // ---- debug ----
            EditorIntent.StartDebug -> startDebug()
            EditorIntent.DebugStep,
            EditorIntent.DebugStepOver -> viewModelScope.launch {
                runCatching { core.debuggerService.stepOver() }
                    .onFailure { t -> if (!t.isShutdownRace()) appendError("step over failed: ${t.message}") }
            }
            EditorIntent.DebugStepInto -> viewModelScope.launch {
                runCatching { core.debuggerService.stepInto() }
                    .onFailure { t -> if (!t.isShutdownRace()) appendError("step into failed: ${t.message}") }
            }
            EditorIntent.DebugStepOut -> viewModelScope.launch {
                runCatching { core.debuggerService.stepOut() }
                    .onFailure { t -> if (!t.isShutdownRace()) appendError("step out failed: ${t.message}") }
            }
            EditorIntent.DebugContinue -> viewModelScope.launch {
                runCatching { core.debuggerService.cont() }
                    .onFailure { t -> if (!t.isShutdownRace()) appendError("continue failed: ${t.message}") }
            }
            EditorIntent.DebugPause -> viewModelScope.launch {
                runCatching { core.debuggerService.pause() }
                    .onFailure { t -> if (!t.isShutdownRace()) appendError("pause failed: ${t.message}") }
            }
            EditorIntent.DebugStop -> viewModelScope.launch {
                runCatching { core.debuggerService.stop() }
            }
            is EditorIntent.ToggleBreakpoint -> {
                val relPath = _state.value.openFile?.relativePath ?: return@handle
                val absPath = core.projectService.resolve(relPath).absolutePath
                viewModelScope.launch {
                    runCatching {
                        core.debuggerService.toggleBreakpoint(absPath, intent.line)
                    }.onFailure { t -> appendError("toggle bp: ${t.message}") }
                }
            }
            is EditorIntent.RemoveBreakpoint -> viewModelScope.launch {
                runCatching {
                    core.debuggerService.toggleBreakpoint(
                        intent.breakpoint.filePath,
                        intent.breakpoint.line,
                    )
                }.onFailure { t -> appendError("remove bp: ${t.message}") }
            }
            is EditorIntent.ToggleVariableExpansion -> {
                val ref = intent.variablesReference
                if (ref <= 0) return@handle
                val s = _state.value
                if (ref in s.expandedVariableRefs) {
                    // Collapse — just drop from the set; cached
                    // children stay so re-expanding is instant.
                    _state.update { it.copy(expandedVariableRefs = it.expandedVariableRefs - ref) }
                } else {
                    // Expand — fetch children if we don't have them
                    // yet, then mark expanded.
                    viewModelScope.launch {
                        if (_state.value.debugVariables[ref] == null) {
                            core.debuggerService.fetchVariables(ref).onSuccess { vars ->
                                _state.update { it.copy(debugVariables = it.debugVariables + (ref to vars)) }
                            }
                        }
                        _state.update { it.copy(expandedVariableRefs = it.expandedVariableRefs + ref) }
                    }
                }
            }

            // ---- chat ----
            is EditorIntent.UpdateChatInput -> _state.update {
                it.copy(chatState = it.chatState.copy(input = intent.text, sendError = null))
            }
            EditorIntent.SendChatMessage -> sendChatMessage()

            // ---- misc ----
            EditorIntent.DismissError -> _state.update { it.copy(errorMessage = null) }
        }
    }

    /** Load chat messages when user switches to the Chat tab for the current file. */
    fun loadChatForCurrentFile() {
        val openFile = _state.value.openFile ?: return
        if (!openFile.relativePath.endsWith(".cpp", ignoreCase = true)) return
        val filePath = buildChatFilePath() ?: return
        if (_state.value.chatLoadedForPath == filePath) return

        _state.update { it.copy(chatState = it.chatState.copy(isLoading = true)) }
        viewModelScope.launch {
            core.chatApi.getMessages(filePath)
                .onSuccess { messages ->
                    _state.update {
                        it.copy(
                            chatState = it.chatState.copy(messages = messages, isLoading = false, unreadCount = 0),
                            chatLoadedForPath = filePath,
                        )
                    }
                    // Reset persisted unread count on the server.
                    core.chatApi.markRead(filePath)
                }
                .onFailure {
                    _state.update { it.copy(chatState = it.chatState.copy(isLoading = false)) }
                }
        }
    }

    /** Silently refresh chat messages (no loading indicator). Called by polling. */
    fun refreshChat() {
        val filePath = buildChatFilePath() ?: return
        if (_state.value.chatLoadedForPath != filePath) return
        viewModelScope.launch {
            core.chatApi.getMessages(filePath)
                .onSuccess { messages ->
                    if (messages != _state.value.chatState.messages) {
                        _state.update {
                            it.copy(chatState = it.chatState.copy(messages = messages))
                        }
                    }
                }
        }
    }

    /**
     * Check for unread messages via the server's persisted unread_count.
     * Called periodically when the chat tab is not active.
     */
    fun checkUnread() {
        viewModelScope.launch {
            core.chatApi.unreadSummary()
                .onSuccess { entries ->
                    val totalUnread = entries.sumOf { it.unreadCount }
                    if (totalUnread != _state.value.chatState.unreadCount) {
                        _state.update {
                            it.copy(chatState = it.chatState.copy(unreadCount = totalUnread))
                        }
                    }
                }
        }
    }

    private fun sendChatMessage() {
        val s = _state.value
        val body = s.chatState.input.trim()
        if (body.isEmpty() || s.chatState.isSending) return

        val openFile = s.openFile ?: return
        val filePath = buildChatFilePath() ?: return
        val (categorySlug, exerciseSlug) = parseSlugs() ?: return

        // Chat requires an account. Surface the login requirement in
        // the chat panel itself instead of firing a broken HTTP request.
        if (!core.studentAuth.isLoggedIn) {
            _state.update {
                it.copy(
                    chatState = it.chatState.copy(
                        isSending = false,
                        sendError = "Please log in to send chat messages.",
                    ),
                )
            }
            return
        }

        _state.update {
            it.copy(chatState = it.chatState.copy(isSending = true, sendError = null))
        }
        viewModelScope.launch {
            val mdPath = openFile.relativePath
                .substringBeforeLast("/") + "/README.md"
            val mdContent = core.projectService.read(mdPath).getOrNull()

            core.chatApi.sendMessage(
                filePath = filePath,
                categorySlug = categorySlug,
                exerciseSlug = exerciseSlug,
                body = body,
                codeSnapshot = openFile.content,
                mdSnapshot = mdContent,
            ).onSuccess { msg ->
                _state.update {
                    it.copy(chatState = it.chatState.copy(
                        messages = it.chatState.messages + msg,
                        input = "",
                        isSending = false,
                        sendError = null,
                    ))
                }
            }.onFailure { t ->
                _state.update {
                    it.copy(
                        chatState = it.chatState.copy(
                            isSending = false,
                            sendError = dev.cppide.ide.util.friendlyNetworkError(t, "Send failed"),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Build the server-side file path from the project-relative path.
     * Exercise projects live under `projects/<category>/<exercise>/`,
     * so we strip the `projects/` prefix to get `<cat>/<ex>/file.cpp`.
     */
    private fun buildChatFilePath(): String? {
        val openFile = _state.value.openFile ?: return null
        // The project root is like .../projects/<category>. The file's
        // relativePath is like "<exercise>/solution.cpp".
        val categorySlug = project.root.name
        return "$categorySlug/${openFile.relativePath}"
    }

    private fun parseSlugs(): Pair<String, String>? {
        val openFile = _state.value.openFile ?: return null
        val categorySlug = project.root.name
        val exerciseSlug = openFile.relativePath.substringBefore("/")
        return categorySlug to exerciseSlug
    }

    // ---- file ops ----

    /**
     * Open a file into a tab. If the file is already open in a tab, just
     * activate it — otherwise read it from disk and append a new tab.
     * Sets [EditorState.fileLoading] while the read is in flight so the
     * screen can show a thin progress bar (large files on slow storage
     * take a visible fraction of a second).
     */
    /**
     * Pre-flight gate for Run / Debug: if clangd's static analysis
     * is currently reporting any error-severity diagnostics, refuse
     * to start, switch to the Problems panel, and emit a terminal
     * line explaining why. Warnings are NOT a blocker — only errors.
     *
     * Returns true if the action was aborted (caller should `return`),
     * false if it's safe to proceed.
     */
    private fun abortIfStaticErrors(action: String): Boolean {
        val errs = _state.value.allProblems.filter { it.isError }
        if (errs.isEmpty()) return false
        appendError("$action aborted: ${errs.size} error${if (errs.size == 1) "" else "s"} reported by static analyzer.")
        appendError("Fix them and try again.")
        _state.update {
            it.copy(
                bottomPanelVisible = true,
                bottomPanelTab = BottomPanelTab.Problems,
            )
        }
        return true
    }

    /**
     * Fetch scopes for the top frame of the current stop, then fetch
     * the variables of the first ("Locals") scope eagerly so the
     * Variables tab has something to show without needing a tap.
     * Other scopes (Globals, Registers) are fetched on demand when
     * the user expands them.
     */
    private fun refreshVariablesForTopFrame(stopped: DebuggerState.Stopped) {
        val topFrameId = stopped.callStack.firstOrNull()?.id ?: return
        viewModelScope.launch {
            core.debuggerService.fetchScopes(topFrameId).onSuccess { scopes ->
                _state.update {
                    it.copy(
                        debugScopes = scopes,
                        debugVariables = emptyMap(),
                        expandedVariableRefs = emptySet(),
                    )
                }
                // Eagerly load + auto-expand the first non-expensive
                // scope (typically "Locals"). Expensive scopes
                // (Registers, Globals) wait for a manual tap.
                val firstCheap = scopes.firstOrNull { !it.expensive && it.variablesReference > 0 }
                if (firstCheap != null) {
                    core.debuggerService.fetchVariables(firstCheap.variablesReference)
                        .onSuccess { vars ->
                            _state.update {
                                it.copy(
                                    debugVariables = it.debugVariables + (firstCheap.variablesReference to vars),
                                    expandedVariableRefs = it.expandedVariableRefs + firstCheap.variablesReference,
                                )
                            }
                        }
                }
            }
        }
    }

    /**
     * When the debugger stops inside a file that isn't the active tab,
     * open it (or switch to its existing tab) so the editor can paint
     * the current-line highlight. Best-effort — if the stopped file is
     * outside the project root (system header, loader, libc), we do
     * nothing and the highlight won't appear.
     */
    private fun autoOpenStoppedFile(absPath: String) {
        val s = _state.value
        // Already looking at the stopped file? Nothing to do.
        if (s.openFile?.let { activeFileAbsPath(it.relativePath) } == absPath) return

        // Try to compute a project-relative path. File system resolution
        // must go through canonicalFile to cope with symlinks (our
        // termux tree is full of them) and trailing-slash variants.
        val root = runCatching { s.project.root.canonicalFile }.getOrNull() ?: return
        val target = runCatching { java.io.File(absPath).canonicalFile }.getOrNull() ?: return
        val rootPath = root.absolutePath.trimEnd('/')
        val targetPath = target.absolutePath
        if (!targetPath.startsWith("$rootPath/") && targetPath != rootPath) {
            // Stopped outside the project (e.g. inside libc++, libc,
            // the loader). Nothing to open.
            return
        }
        val relativePath = targetPath.removePrefix("$rootPath/")
        if (relativePath.isBlank()) return

        // Go through the same tab/LSP plumbing as a manual OpenFile,
        // but with stopDebugOnSwitch=false — we're switching BECAUSE
        // the debugger stopped, so killing the session would be absurd.
        openFile(relativePath, stopDebugOnSwitch = false)
    }

    private fun activeFileAbsPath(relativePath: String): String? =
        runCatching { core.projectService.resolve(relativePath).absolutePath }.getOrNull()

    private fun openFile(relativePath: String, stopDebugOnSwitch: Boolean = true) {
        // Close the drawer and flip the loading flag synchronously —
        // BEFORE touching the filesystem. The previous version waited for
        // the disk read to complete before closing the drawer, which on
        // slower storage left the drawer sitting on screen for a second
        // or two after the tap, feeling like the tap was ignored.
        _state.update { it.copy(drawerOpen = false, fileLoading = true) }

        viewModelScope.launch {
            // If the file is already open as a tab, just switch.
            val existing = _state.value.openTabs.indexOfFirst { it.relativePath == relativePath }
            if (existing >= 0) {
                _state.update { it.copy(activeTabIndex = existing, fileLoading = false) }
                persistUiState()
                return@launch
            }

            // Tear down any live debug session before switching — the
            // debuggee was compiled from the old file, so stepping into
            // it while the editor shows a different file is confusing
            // at best and misleading at worst. Skipped when the
            // debugger itself is driving this navigation (auto-open
            // on stop in a cross-file frame).
            if (stopDebugOnSwitch && _state.value.debuggerState.isActive) {
                runCatching { core.debuggerService.stop() }
            }

            core.projectService.read(relativePath)
                .onSuccess { content ->
                    _state.update { s ->
                        val newTab = OpenFile(relativePath, content, content)
                        val tabs = s.openTabs + newTab
                        s.copy(
                            openTabs = tabs,
                            activeTabIndex = tabs.lastIndex,
                            fileLoading = false,
                        )
                    }
                    persistUiState()
                    // Record in recent files for the Welcome screen.
                    val file = core.projectService.resolve(relativePath)
                    val fileName = relativePath.substringAfterLast("/")
                    if (fileName.endsWith(".cpp", ignoreCase = true)) {
                        val exerciseName = relativePath.substringBefore("/").slugToTitle()
                        core.sessionRepository.touchFile(
                            filePath = file.absolutePath,
                            projectRoot = project.root.absolutePath,
                            projectName = project.name,
                            relativePath = relativePath,
                            displayName = exerciseName,
                        )
                    }
                    // Tell clangd we opened the file. Language id by extension.
                    val lang = if (file.extension.lowercase() == "c") "c" else "cpp"
                    core.lspService.didOpen(file, lang, content)
                }
                .onFailure { t ->
                    _state.update { it.copy(errorMessage = "open: ${t.message}", fileLoading = false) }
                }
        }
    }

    private fun selectTab(index: Int) {
        val s = _state.value
        if (index !in s.openTabs.indices) return
        if (index == s.activeTabIndex) return
        if (s.debuggerState.isActive) {
            viewModelScope.launch { runCatching { core.debuggerService.stop() } }
        }
        _state.update { it.copy(activeTabIndex = index, chatState = ChatPanelState(), chatLoadedForPath = null) }
        persistUiState()
    }

    private fun closeTab(index: Int) {
        viewModelScope.launch {
            val s = _state.value
            if (index !in s.openTabs.indices) return@launch
            // Flush any pending edits in the tab being closed before
            // dropping it, so the user doesn't lose work on a fast close.
            val closing = s.openTabs[index]
            if (closing.isDirty) {
                runCatching { core.projectService.write(closing.relativePath, closing.content) }
            }
            // Tell clangd we're done with the file.
            val file = core.projectService.resolve(closing.relativePath)
            runCatching { core.lspService.didClose(file) }

            _state.update { cur ->
                val tabs = cur.openTabs.toMutableList().also { it.removeAt(index) }
                val newActive = when {
                    tabs.isEmpty() -> null
                    cur.activeTabIndex == null -> null
                    index < cur.activeTabIndex -> cur.activeTabIndex - 1
                    index == cur.activeTabIndex -> index.coerceAtMost(tabs.lastIndex)
                    else -> cur.activeTabIndex
                }
                cur.copy(openTabs = tabs, activeTabIndex = newActive)
            }
            persistUiState()
        }
    }

    private fun shareActiveFile() {
        val openFile = _state.value.openFile ?: return
        _events.tryEmit(EditorEvent.ShareFile(openFile.name, openFile.content))
    }

    private fun createFile(parentRelativePath: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.any { it == '/' || it == '\\' || it == ':' }) {
            _state.update { it.copy(errorMessage = "invalid file name: $name") }
            return
        }
        val rel = if (parentRelativePath.isEmpty()) trimmed else "$parentRelativePath/$trimmed"
        // Seed .cpp / .c files with a tiny runnable main so the student
        // can hit Run immediately instead of staring at an empty buffer.
        // Other extensions get an empty file — we don't want to assume
        // what a .md or .txt should contain.
        val initialContent = when {
            rel.endsWith(".cpp", ignoreCase = true) ||
                rel.endsWith(".cc", ignoreCase = true) ||
                rel.endsWith(".cxx", ignoreCase = true) -> STARTER_CPP
            rel.endsWith(".c", ignoreCase = true) -> STARTER_C
            else -> ""
        }
        viewModelScope.launch {
            core.projectService.createFile(rel, initialContent)
                .onSuccess {
                    _state.update { it.copy(fileTree = core.projectService.fileTree.value) }
                    handle(EditorIntent.OpenFile(rel))
                }
                .onFailure { t -> _state.update { it.copy(errorMessage = "create: ${t.message}") } }
        }
    }

    private fun deleteFile(relativePath: String) {
        viewModelScope.launch {
            // If the file is open as a tab, drop it first so the editor
            // doesn't hold a stale reference to a path that no longer exists.
            val tabIndex = _state.value.openTabs.indexOfFirst { it.relativePath == relativePath }
            if (tabIndex >= 0) {
                val file = core.projectService.resolve(relativePath)
                runCatching { core.lspService.didClose(file) }
                _state.update { s ->
                    val tabs = s.openTabs.toMutableList().also { it.removeAt(tabIndex) }
                    val newActive = when {
                        tabs.isEmpty() -> null
                        s.activeTabIndex == null -> null
                        tabIndex < s.activeTabIndex -> s.activeTabIndex - 1
                        tabIndex == s.activeTabIndex -> tabIndex.coerceAtMost(tabs.lastIndex)
                        else -> s.activeTabIndex
                    }
                    s.copy(openTabs = tabs, activeTabIndex = newActive)
                }
                persistUiState()
            }
            core.projectService.delete(relativePath)
                .onSuccess {
                    _state.update { it.copy(fileTree = core.projectService.fileTree.value) }
                }
                .onFailure { t -> _state.update { it.copy(errorMessage = "delete: ${t.message}") } }
        }
    }

    private fun renameFile(relativePath: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed.any { it == '/' || it == '\\' || it == ':' }) {
            _state.update { it.copy(errorMessage = "invalid file name: $newName") }
            return
        }
        val parent = relativePath.substringBeforeLast('/', "")
        val target = if (parent.isEmpty()) trimmed else "$parent/$trimmed"
        if (target == relativePath) return
        viewModelScope.launch {
            // Flush any pending edits in the tab before rename so the old
            // path contains the user's latest content.
            val tabIndex = _state.value.openTabs.indexOfFirst { it.relativePath == relativePath }
            if (tabIndex >= 0) {
                val tab = _state.value.openTabs[tabIndex]
                if (tab.isDirty) {
                    runCatching { core.projectService.write(relativePath, tab.content) }
                }
                runCatching {
                    core.lspService.didClose(core.projectService.resolve(relativePath))
                }
            }
            core.projectService.rename(relativePath, target)
                .onSuccess {
                    _state.update { s ->
                        val tabs = s.openTabs.toMutableList()
                        if (tabIndex >= 0) {
                            tabs[tabIndex] = tabs[tabIndex].copy(
                                relativePath = target,
                                savedContent = tabs[tabIndex].content,
                            )
                        }
                        s.copy(
                            openTabs = tabs,
                            fileTree = core.projectService.fileTree.value,
                        )
                    }
                    if (tabIndex >= 0) {
                        val file = core.projectService.resolve(target)
                        val lang = if (file.extension.lowercase() == "c") "c" else "cpp"
                        val content = _state.value.openTabs[tabIndex].content
                        runCatching { core.lspService.didOpen(file, lang, content) }
                    }
                    persistUiState()
                }
                .onFailure { t -> _state.update { it.copy(errorMessage = "rename: ${t.message}") } }
        }
    }

    private fun createDirectory(parentRelativePath: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.any { it == '/' || it == '\\' || it == ':' }) {
            _state.update { it.copy(errorMessage = "invalid folder name: $name") }
            return
        }
        val rel = if (parentRelativePath.isEmpty()) trimmed else "$parentRelativePath/$trimmed"
        viewModelScope.launch {
            core.projectService.createDirectory(rel)
                .onSuccess {
                    _state.update { it.copy(fileTree = core.projectService.fileTree.value) }
                }
                .onFailure { t -> _state.update { it.copy(errorMessage = "mkdir: ${t.message}") } }
        }
    }

    /**
     * Restore the tab set persisted from a previous session. If no persisted
     * state exists, fall back to: first main.cpp under root, else first
     * file found in a DFS walk of the tree.
     *
     * Reads happen inline on the caller's coroutine so that by the time the
     * init block flips [EditorState.projectLoading] off the tabs already
     * contain content — preventing a multi-second window where the editor
     * shows an empty pane after the welcome→editor transition.
     */
    private suspend fun restoreOrSeedTabs(tree: dev.cppide.core.project.ProjectNode.Directory?) {
        tree ?: return
        // If the route already asked for a specific file via
        // `initialOpenFile`, that OpenFile intent may have raced ahead
        // of this coroutine and populated openTabs already. Clobbering
        // those tabs here would make the editor land on the persisted
        // last-active file instead of the one the user just clicked
        // from the Recent Files list — a confusing bug where tapping
        // 5.cpp would open 2.cpp. Bail out and let the route's choice
        // win.
        if (_state.value.openTabs.isNotEmpty()) return
        val saved = ProjectUiState.load(core.context, _state.value.project.root)
        val paths = saved.openPaths.filter { pathExistsInTree(tree, it) }
        val toOpen = if (paths.isNotEmpty()) paths else listOfNotNull(
            tree.children.firstOrNull { it.name == "main.cpp" }?.relativePath
                ?: firstFileIn(tree),
        )
        if (toOpen.isEmpty()) return

        val loaded = mutableListOf<OpenFile>()
        for (path in toOpen) {
            val content = core.projectService.read(path).getOrNull() ?: continue
            loaded.add(OpenFile(path, content, content))
        }
        if (loaded.isEmpty()) return

        val activePath = saved.activePath?.takeIf { paths.isNotEmpty() }
        val activeIdx = loaded.indexOfFirst { it.relativePath == activePath }
            .takeIf { it >= 0 } ?: 0

        var applied = false
        _state.update { s ->
            // Second check inside the update closure to cover the case
            // where OpenFile landed between the earlier guard and here.
            if (s.openTabs.isNotEmpty()) s
            else {
                applied = true
                s.copy(openTabs = loaded, activeTabIndex = activeIdx)
            }
        }
        if (!applied) return
        persistUiState()

        // Tell clangd about each restored tab. didOpen is idempotent from
        // the server's POV once clangd is ready; if it isn't yet, the
        // lspState collector replays didOpen for the active file on the
        // Ready transition.
        for (tab in loaded) {
            val file = core.projectService.resolve(tab.relativePath)
            val lang = if (file.extension.lowercase() == "c") "c" else "cpp"
            runCatching { core.lspService.didOpen(file, lang, tab.content) }
        }
    }

    private fun pathExistsInTree(
        tree: dev.cppide.core.project.ProjectNode.Directory,
        path: String,
    ): Boolean {
        fun walk(node: dev.cppide.core.project.ProjectNode): Boolean = when (node) {
            is dev.cppide.core.project.ProjectNode.File -> node.relativePath == path
            is dev.cppide.core.project.ProjectNode.Directory -> node.children.any { walk(it) }
        }
        return walk(tree)
    }

    private fun firstFileIn(node: dev.cppide.core.project.ProjectNode): String? = when (node) {
        is dev.cppide.core.project.ProjectNode.File -> node.relativePath
        is dev.cppide.core.project.ProjectNode.Directory ->
            node.children.asSequence().mapNotNull { firstFileIn(it) }.firstOrNull()
    }

    private fun persistUiState() {
        val s = _state.value
        ProjectUiState.save(
            context = core.context,
            projectRoot = s.project.root,
            state = ProjectUiState(
                openPaths = s.openTabs.map { it.relativePath },
                activePath = s.openFile?.relativePath,
            ),
        )
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
        val activeIndex = _state.value.activeTabIndex ?: return true
        val openFile = _state.value.openTabs.getOrNull(activeIndex) ?: return true
        if (!openFile.isDirty) return true
        _state.update { it.copy(saving = true) }
        return core.projectService.write(openFile.relativePath, openFile.content)
            .fold(
                onSuccess = {
                    _state.update { s ->
                        val tabs = s.openTabs.toMutableList()
                        val idx = tabs.indexOfFirst { it.relativePath == openFile.relativePath }
                        if (idx >= 0) {
                            tabs[idx] = tabs[idx].copy(savedContent = tabs[idx].content)
                        }
                        s.copy(
                            openTabs = tabs,
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

            // 0b. Pre-run static analysis check. If clangd is reporting
            // any error-severity diagnostics for the open file, abort
            // and surface the Problems panel instead of compiling code
            // we already know is broken. Warnings don't block.
            if (abortIfStaticErrors("Run")) return@launch

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
            // Progress updates land on ToolchainState via install() already,
            // which the UI surfaces as a spinner status. Don't spam the
            // terminal with per-500-file "extracted N files (M MB)" lines.
            val install = core.toolchain.install { /* silent */ }
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
                shared = true,
                wrapMain = true,
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

        core.runService.run(
            RunConfig(library = library, stdin = terminalStdin),
        ).collect { event ->
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
     * Forward a line of user input to the running inferior's stdin. Appends
     * `\n` unconditionally — line-based programs (cin >> x, getline) need it,
     * and the few byte-oriented programs that don't can still parse it out.
     * No-op when nothing is running; we also echo the submitted text into the
     * terminal panel so the user sees what they sent.
     */
    private fun sendTerminalInput(text: String) {
        // Accept during Run (runState=Running) OR during an active debug
        // session — debug is tracked on a separate state machine.
        val debugActive = core.debuggerService.state.value.isActive
        if (_state.value.runState != RunState.Running && !debugActive) return
        val line = text + "\n"
        appendStdout(line)
        if (!terminalStdin.tryEmit(line)) {
            appendError("stdin buffer full — input dropped")
        }
    }

    // ---- debug pipeline ----

    /**
     * Debug-build and launch the current file under lldb-server. Mirrors
     * [runCurrent] but:
     *  - Compiles with -O0 + -g (debug info, unoptimized so stepping is
     *    predictable) and -gz=none so our Phase 2 DWARF parser doesn't
     *    have to deal with compressed sections.
     *  - Instead of dlopening the .so in-process, spawns the trampoline
     *    as a separate child under lldb-server, which ptraces it.
     *
     * The built .so lands in a sibling path to the Run output so a
     * subsequent Run tap doesn't pick up debug binaries (and vice versa).
     */
    private fun startDebug() {
        debugJob?.cancel()
        debugJob = viewModelScope.launch {
            val openFile = _state.value.openFile ?: run {
                appendInfo("No file open"); return@launch
            }

            // 0. Save in-flight edits.
            if (!save()) return@launch

            // 0b. Same pre-flight static-analysis gate as Run. If clangd
            // is reporting compile errors, debugging would just fail at
            // build time anyway — better to show the user the problem
            // up front instead of after an asset extraction round-trip.
            if (abortIfStaticErrors("Debug")) return@launch

            // Open the terminal panel immediately so the FAB hides
            // through the toolchain install + build phase instead of
            // lingering visibly while work happens off-screen.
            _state.update {
                it.copy(
                    bottomPanelVisible = true,
                    bottomPanelTab = BottomPanelTab.Terminal,
                )
            }

            // 1. Toolchain install (idempotent).
            val install = core.toolchain.install()
            if (install.isFailure) {
                appendError("Toolchain install failed: ${install.exceptionOrNull()?.message}")
                return@launch
            }

            // 2. Debug build. Separate output file from Run so both can
            // coexist on disk without cross-contamination. Show the
            // Terminal tab by default while debugging so the user
            // sees inferior stdout immediately; they can switch to
            // Variables manually when they want to inspect locals.
            _state.update { it.copy(
                bottomPanelVisible = true,
                bottomPanelTab = BottomPanelTab.Terminal,
                problems = emptyList(),
            ) }
            val source = core.projectService.resolve(openFile.relativePath)
            val buildDir = File(core.context.filesDir, "build-debug/${state.value.project.name}")
            buildDir.mkdirs()
            val outputSo = File(buildDir, "libuser-debug.so")

            val config = BuildConfig(
                projectRoot = state.value.project.root,
                sources = listOf(source),
                output = outputSo,
                targetApi = 26,
                optimization = BuildConfig.Optimization.O0,
                shared = true,
                wrapMain = true,
                runtimeShim = core.runtimeShimSource(),
                extraFlags = listOf("-g", "-gz=none"),
            )

            val buildResult = core.buildService.build(config)
            when (buildResult) {
                is BuildResult.Success -> {
                    _state.update { it.copy(problems = buildResult.diagnostics) }
                }
                is BuildResult.Failure -> {
                    appendError("Debug build failed (exit ${buildResult.exitCode}, ${buildResult.diagnostics.count { it.isError }} errors)")
                    _state.update { it.copy(
                        problems = buildResult.diagnostics,
                        bottomPanelTab = if (buildResult.diagnostics.isNotEmpty())
                            BottomPanelTab.Problems else BottomPanelTab.Variables,
                    ) }
                    return@launch
                }
                is BuildResult.Error -> {
                    appendError("Debug build error: ${buildResult.message}")
                    return@launch
                }
            }

            // 3. Launch the debugger. Trampoline lives alongside the other
            // jniLibs-masqueraded binaries — same directory as libclangd.so.
            val trampoline = File(
                core.context.applicationInfo.nativeLibraryDir,
                "libTrampoline.so"
            )
            if (!trampoline.exists()) {
                appendError("Trampoline binary missing at ${trampoline.absolutePath}")
                return@launch
            }
            core.debuggerService.start(
                trampolineBinary = trampoline,
                userLibrary = outputSo,
                projectRoot = state.value.project.root,
                stdin = terminalStdin,
            ).onFailure { t -> appendError("debugger start failed: ${t.message}") }
        }
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
        // Tear down on a scope we control, NOT viewModelScope — that
        // scope is being cancelled as part of onCleared and any launches
        // we make here would race with the cancellation (clangd might
        // leak a 200 MB process, lldb-server might leak an orphan).
        // runBlocking gives us a synchronous barrier; we're already off
        // any UI thread by the time onCleared is invoked from the
        // ViewModelStore, so blocking is safe.
        runBlocking {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { core.debuggerService.stop() }
                runCatching { core.lspService.stop() }
            }
        }
    }

    /** Is this throwable the expected "we tore down mid-flight" signal, or
     *  a real bug? Used to filter "continue failed: Channel was closed"
     *  noise from the terminal when the user navigates away during a
     *  debug session — cleanup races with in-flight commands and that's
     *  fine. */
    private fun Throwable.isShutdownRace(): Boolean {
        val n = this::class.java.simpleName
        return n == "ClosedReceiveChannelException" ||
            n == "CancellationException" ||
            message?.contains("Channel was closed") == true
    }

    companion object {
        private const val TAG = "cppide-editor"
        /** How long after the last keystroke to auto-save. */
        private const val AUTO_SAVE_DELAY_MS = 1_000L
        /** How long after the last keystroke to push didChange to clangd. */
        private const val LSP_CHANGE_DEBOUNCE_MS = 200L

        /** Starter content for newly-created .cpp / .cc / .cxx files. */
        private val STARTER_CPP = """
            |#include <iostream>
            |using namespace std;
            |
            |int main() {
            |    return 0;
            |}
            |
        """.trimMargin()

        /** Starter content for newly-created .c files. */
        private val STARTER_C = """
            |#include <stdio.h>
            |
            |int main(void) {
            |    return 0;
            |}
            |
        """.trimMargin()
    }
}
