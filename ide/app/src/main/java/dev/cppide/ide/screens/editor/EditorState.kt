package dev.cppide.ide.screens.editor

import dev.cppide.core.build.Diagnostic
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
    val openFile: OpenFile? = null,
    val drawerOpen: Boolean = false,
    val saving: Boolean = false,
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
) {
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

enum class BottomPanelTab { Terminal, Problems }
