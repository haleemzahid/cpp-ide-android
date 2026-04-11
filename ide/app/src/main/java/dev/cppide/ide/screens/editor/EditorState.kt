package dev.cppide.ide.screens.editor

import dev.cppide.core.build.Diagnostic
import dev.cppide.core.debug.BreakpointState
import dev.cppide.core.debug.DebuggerState
import dev.cppide.core.debug.SourceBreakpoint
import dev.cppide.core.lsp.LspDiagnostic
import dev.cppide.core.lsp.LspState
import dev.cppide.core.project.Project
import dev.cppide.core.project.ProjectNode

/**
 * Immutable snapshot of the editor screen. The ViewModel emits new
 * instances; the UI re-renders. No mutable state on the UI side.
 */
data class EditorState(
    val project: Project,

    // ---- file system ----
    val fileTree: ProjectNode.Directory? = null,
    /** All currently open editor tabs, in tab-strip order. */
    val openTabs: List<OpenFile> = emptyList(),
    /** Index into [openTabs] for the active tab, or null if no tabs open. */
    val activeTabIndex: Int? = null,
    val drawerOpen: Boolean = false,
    val saving: Boolean = false,
    /** True while a file read is in flight (drives the editor loading bar). */
    val fileLoading: Boolean = false,
    /** True while the initial project open + tab restore is in flight. */
    val projectLoading: Boolean = true,
    /** When viewing a .md file, true = rendered preview, false = raw source. */
    val markdownPreview: Boolean = true,
    val errorMessage: String? = null,

    // ---- build / run ----
    val runState: RunState = RunState.Idle,
    val bottomPanelVisible: Boolean = false,
    val bottomPanelTab: BottomPanelTab = BottomPanelTab.Terminal,
    val terminalLines: List<TerminalLine> = emptyList(),
    val problems: List<Diagnostic> = emptyList(),

    // ---- LSP / IntelliSense ----
    val lspState: LspState = LspState.NotStarted,
    val lspDiagnosticsByFile: Map<String, List<LspDiagnostic>> = emptyMap(),

    // ---- debugger ----
    val debuggerState: DebuggerState = DebuggerState.Idle,
    /** All user breakpoints, keyed by (file, line). Survives debug sessions. */
    val breakpoints: Map<SourceBreakpoint, BreakpointState> = emptyMap(),
) {
    /** The currently active tab's file, or null when no tabs are open. */
    val openFile: OpenFile?
        get() = activeTabIndex?.let { openTabs.getOrNull(it) }

    val errorCount: Int get() = allProblems.count { it.isError }
    val warningCount: Int get() = allProblems.count { it.severity == Diagnostic.Severity.WARNING }
    val isBusy: Boolean get() = runState != RunState.Idle

    /** LSP diagnostics for the currently open file, or empty if none. */
    val lspDiagnostics: List<LspDiagnostic>
        get() {
            // Match the key format used by ClangdLspService.publishDiagnostics:
            // absolute filesystem path, NOT a file:// URI string. URI
            // normalisation differs between Java versions ("file:/" vs
            // "file:///") so paths are the safer common ground.
            val absPath = openFile?.let {
                java.io.File(project.root, it.relativePath).absolutePath
            } ?: return emptyList()
            return lspDiagnosticsByFile[absPath] ?: emptyList()
        }

    /**
     * Unified problem list shown in the Problems panel — last build's
     * structured diagnostics PLUS clangd's live diagnostics for the
     * currently open file. Build diagnostics come first so the user
     * sees the build state alongside semantic editor errors.
     */
    val allProblems: List<Diagnostic>
        get() = problems + lspDiagnostics.map { it.toBuildDiagnostic() }

    /** Breakpoints that apply to whatever file is currently open,
     *  indexed by 1-based line so the editor gutter can look them up
     *  in O(1) while rendering. */
    val breakpointLinesForOpenFile: Map<Int, BreakpointState>
        get() {
            val openBasename = openFile?.relativePath
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?: return emptyMap()
            return breakpoints.values
                .filter { it.source.fileBasename == openBasename }
                .associateBy { it.line }
        }
}

/**
 * A file the user has loaded into the editor. [savedContent] is the
 * version on disk; [content] is what's currently in the editor buffer.
 * [isDirty] is true when they diverge.
 */
data class OpenFile(
    val relativePath: String,
    val content: String,
    val savedContent: String,
) {
    val name: String get() = relativePath.substringAfterLast('/')
    val isDirty: Boolean get() = content != savedContent
}

enum class RunState {
    Idle,
    InstallingToolchain,
    Building,
    Running,
}

enum class BottomPanelTab { Terminal, Problems, Debug }
