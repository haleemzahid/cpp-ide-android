package dev.cppide.ide.screens.editor

import dev.cppide.core.build.Diagnostic
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
) {
    val errorCount: Int get() = problems.count { it.isError }
    val warningCount: Int get() = problems.count { it.severity == Diagnostic.Severity.WARNING }
    val isBusy: Boolean get() = runState != RunState.Idle
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
