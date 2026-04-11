package dev.cppide.ide.screens.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.cppide.core.lsp.LspCompletion
import dev.cppide.ide.components.MarkdownView
import dev.cppide.ide.screens.editor.components.BottomPanel
import dev.cppide.ide.screens.editor.components.EditorController
import dev.cppide.ide.screens.editor.components.EditorPane
import dev.cppide.ide.screens.editor.components.EditorTabBar
import dev.cppide.ide.screens.editor.components.EditorTopBar
import dev.cppide.ide.screens.editor.components.EmptyEditorPane
import dev.cppide.ide.screens.editor.components.FileTreeDrawer
import dev.cppide.ide.screens.editor.components.RunFab
import dev.cppide.ide.theme.CppIde

/**
 * Stateless editor screen — receives [EditorState] and emits [EditorIntent]s.
 *
 * Layout:
 *   ┌──────────────────────────────────┐
 *   │ EditorTopBar                     │
 *   ├──────────────────────────────────┤
 *   │ Tab strip (open files)           │
 *   ├──────────────────────────────────┤
 *   │                                  │
 *   │      EditorPane (active file)    │ ← weight(1f)
 *   │                                  │
 *   ├──────────────────────────────────┤
 *   │ BottomPanel (Terminal/Problems)  │ ← shown only if visible
 *   └──────────────────────────────────┘
 *                                  ┌─┐
 *                                  │▶│  ← FAB overlay (BottomEnd)
 *                                  └─┘
 *
 * The drawer slides over everything as a modal layer when open.
 */
@Composable
fun EditorScreen(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    onBack: () -> Unit,
    onRequestCompletion: suspend (liveContent: String, line: Int, column: Int) -> List<LspCompletion>,
    onRequestHover: suspend (line: Int, column: Int) -> String?,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    // Handle to the live sora editor, published by EditorPane once it's
    // been constructed. Held here so the top bar can drive undo/redo
    // without the screen or top bar needing to know about sora at all.
    var controller by remember { mutableStateOf<EditorController?>(null) }

    // System back: dismiss overlays first, then return to welcome.
    // Without this, back falls through to the activity and exits the app.
    BackHandler {
        when {
            state.drawerOpen -> onIntent(EditorIntent.CloseDrawer)
            state.bottomPanelVisible -> onIntent(EditorIntent.ToggleBottomPanel)
            else -> onBack()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding(),
    ) {
        // ---- main column: top bar + editor body + bottom panel ----
        Column(modifier = Modifier.fillMaxSize()) {
            val isMarkdown = state.openFile?.name?.endsWith(".md", ignoreCase = true) == true
            EditorTopBar(
                projectName = state.project.name,
                activeFileName = state.openFile?.name,
                isDirty = state.openFile?.isDirty == true,
                canShare = state.openFile != null,
                isMarkdown = isMarkdown,
                markdownPreview = state.markdownPreview,
                onBack = onBack,
                onToggleDrawer = { onIntent(EditorIntent.ToggleDrawer) },
                onUndo = { controller?.undo() },
                onRedo = { controller?.redo() },
                onSave = { onIntent(EditorIntent.Save) },
                onShare = { onIntent(EditorIntent.ShareActiveFile) },
                onToggleMarkdownPreview = {
                    onIntent(EditorIntent.ToggleMarkdownPreview)
                },
            )

            // Thin progress bar driven by project-level + file-level
            // loading flags. Kept to 2dp so it's a subtle hint, not a modal.
            if (state.projectLoading || state.fileLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = colors.accent,
                    trackColor = Color.Transparent,
                )
            }

            if (state.openTabs.isNotEmpty()) {
                EditorTabBar(
                    tabs = state.openTabs,
                    activeIndex = state.activeTabIndex,
                    onSelect = { onIntent(EditorIntent.SelectTab(it)) },
                    onClose = { onIntent(EditorIntent.CloseTab(it)) },
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val openFile = state.openFile
                if (openFile == null) {
                    EmptyEditorPane(
                        modifier = Modifier.fillMaxSize(),
                        loading = state.projectLoading || state.fileLoading,
                    )
                } else if (isMarkdown && state.markdownPreview) {
                    MarkdownView(
                        markdown = openFile.content,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    EditorPane(
                        // Stable per-file id so the editor recreates only when
                        // the user opens a different file, not on every keystroke.
                        fileId = openFile.relativePath,
                        initialContent = openFile.savedContent,
                        onContentChange = { onIntent(EditorIntent.EditContent(it)) },
                        onRequestCompletion = onRequestCompletion,
                        onRequestHover = onRequestHover,
                        onToggleBreakpoint = { line ->
                            onIntent(EditorIntent.ToggleBreakpoint(line))
                        },
                        onControllerReady = { controller = it },
                        breakpointLines = state.breakpointLinesForOpenFile
                            .mapValues { (_, bp) -> bp.verified },
                        currentLine = (state.debuggerState as? dev.cppide.core.debug.DebuggerState.Stopped)
                            ?.takeIf { stopped ->
                                val openBasename = openFile.name
                                stopped.sourceFile?.substringAfterLast('/')
                                    ?.substringAfterLast('\\') == openBasename
                            }?.sourceLine,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (state.bottomPanelVisible) {
                BottomPanel(
                    activeTab = state.bottomPanelTab,
                    terminalLines = state.terminalLines,
                    problems = state.allProblems,
                    debuggerState = state.debuggerState,
                    breakpoints = state.breakpoints,
                    onSelectTab = { onIntent(EditorIntent.SwitchBottomTab(it)) },
                    onClose = { onIntent(EditorIntent.ToggleBottomPanel) },
                    onClearTerminal = { onIntent(EditorIntent.ClearTerminal) },
                    onJumpToProblem = { onIntent(EditorIntent.JumpToDiagnostic(it)) },
                    onStartDebug = { onIntent(EditorIntent.StartDebug) },
                    onDebugStep = { onIntent(EditorIntent.DebugStep) },
                    onDebugContinue = { onIntent(EditorIntent.DebugContinue) },
                    onDebugPause = { onIntent(EditorIntent.DebugPause) },
                    onDebugStop = { onIntent(EditorIntent.DebugStop) },
                    onToggleBreakpoint = { bp ->
                        onIntent(EditorIntent.RemoveBreakpoint(bp))
                    },
                )
            }
        }

        // ---- run/stop FAB (bottom-right) ----
        // Hide the FAB for non-runnable files (e.g. README.md preview).
        val openPath = state.openFile?.relativePath.orEmpty()
        val isRunnableFile = !openPath.endsWith(".md", ignoreCase = true)
        if (isRunnableFile) {
            RunFab(
                runState = state.runState,
                onClick = { onIntent(EditorIntent.RunOrStop) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = dimens.spacingL, bottom = dimens.spacingL)
                    .navigationBarsPadding(),
            )
        }

        // ---- modal drawer overlay ----
        if (state.drawerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { onIntent(EditorIntent.CloseDrawer) },
            )
            FileTreeDrawer(
                projectName = state.project.name,
                tree = state.fileTree,
                activePath = state.openFile?.relativePath,
                onFileClick = { path -> onIntent(EditorIntent.OpenFile(path)) },
                onCreateFile = { parent, name ->
                    onIntent(EditorIntent.CreateFile(parent, name))
                },
                onCreateDirectory = { parent, name ->
                    onIntent(EditorIntent.CreateDirectory(parent, name))
                },
                onDeleteFile = { path -> onIntent(EditorIntent.DeleteFile(path)) },
                onRenameFile = { path, newName ->
                    onIntent(EditorIntent.RenameFile(path, newName))
                },
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
    }
}
