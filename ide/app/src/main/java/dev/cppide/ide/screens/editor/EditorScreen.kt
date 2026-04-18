package dev.cppide.ide.screens.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import dev.cppide.ide.screens.editor.components.FloatingDebugPanel
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.cppide.core.lsp.LspCompletion
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.components.CppHorizontalDivider
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
 *   │ CollapsedPanelBar (when hidden)  │ ← tap to open bottom panel
 *   │  — OR —                          │
 *   │ BottomPanel (when visible)       │
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
    onChatOpened: () -> Unit,
    onChatRefresh: () -> Unit,
    onCheckUnread: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    var controller by remember { mutableStateOf<EditorController?>(null) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }

    // Reload chat messages whenever the chat tab is the active bottom
    // panel AND the active source file changes. Keying only on
    // `chatActive` would leave stale messages after a tab-bar or
    // file-tree switch.
    val chatActive = state.bottomPanelVisible && state.bottomPanelTab == BottomPanelTab.Chat
    val chatActiveForFile = state.openFile?.relativePath?.takeIf { chatActive }
    LaunchedEffect(chatActiveForFile) {
        chatActiveForFile?.let { onChatOpened() }
    }

    // Poll for new messages every 5s while the chat tab is visible.
    // Check for unread every 10s when chat tab is NOT visible (for badge).
    LaunchedEffect(chatActive) {
        if (chatActive) {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                onChatRefresh()
            }
        } else {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                onCheckUnread()
            }
        }
    }

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
        Column(modifier = Modifier.fillMaxSize()) {
            val isMarkdown = state.openFile?.name?.endsWith(".md", ignoreCase = true) == true
            EditorTopBar(
                projectName = state.project.name,
                activeFileName = state.openFile?.name,
                isDirty = state.openFile?.isDirty == true,
                canShare = state.openFile != null,
                canUndo = canUndo,
                canRedo = canRedo,
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
                        fileId = openFile.relativePath,
                        initialContent = openFile.savedContent,
                        onContentChange = { onIntent(EditorIntent.EditContent(it)) },
                        onHistoryChange = { u, r -> canUndo = u; canRedo = r },
                        onRequestCompletion = onRequestCompletion,
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
                        inlineDebugValues = state.inlineDebugValuesForOpenFile,
                        lspDiagnostics = state.lspDiagnostics,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Floating VSCode-style debug toolbar overlay. Lives
                // INSIDE the editor box so it tracks the editor's
                // bounds (not the bottom panel or top app bar). The
                // panel composable fills the whole editor box so it
                // can measure those bounds for drag clamping; inside,
                // it positions the actual toolbar top-center and
                // applies the user's drag offset. Touches on empty
                // space pass through to the editor underneath.
                FloatingDebugPanel(
                    debuggerState = state.debuggerState,
                    onIntent = onIntent,
                    modifier = Modifier.fillMaxSize(),
                )

                // FAB lives inside the editor Box (not at screen root)
                // so it rides above the bottom panel when the panel is
                // open, instead of being hidden behind it.
                if (!isMarkdown && !state.debuggerState.isActive) {
                    RunFab(
                        runState = state.runState,
                        onRun = { onIntent(EditorIntent.RunOrStop) },
                        onDebug = { onIntent(EditorIntent.StartDebug) },
                        onStop = { onIntent(EditorIntent.RunOrStop) },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = dimens.spacingL, bottom = dimens.spacingL),
                    )
                }
            }

            val ext = state.openFile?.name?.substringAfterLast('.', "")?.lowercase()
            val isCodeFile = ext in setOf("cpp", "c", "cc", "cxx", "h", "hpp")

            if (state.bottomPanelVisible) {
                BottomPanel(
                    activeTab = state.bottomPanelTab,
                    terminalLines = state.terminalLines,
                    problems = state.allProblems,
                    debuggerState = state.debuggerState,
                    debugScopes = state.debugScopes,
                    debugVariables = state.debugVariables,
                    expandedVariableRefs = state.expandedVariableRefs,
                    chatState = state.chatState,
                    isCppFile = isCodeFile,
                    // Accept terminal input only when the program is
                    // genuinely executing: plain Run, or debug mid-step
                    // (Starting/Running). Paused (`Stopped`) means the
                    // inferior is frozen — any keys the user types
                    // won't reach stdin until they resume, which is
                    // confusing, so we disable the input row entirely
                    // during the pause.
                    isRunning = state.runState == RunState.Running ||
                        (state.debuggerState.isActive &&
                            state.debuggerState !is dev.cppide.core.debug.DebuggerState.Stopped),
                    // Auto-open the IME whenever the program is truly
                    // executing. This mirrors `isRunning` exactly —
                    // during a Debug pause (`Stopped`) the input row
                    // is disabled, and we also actively dismiss the
                    // keyboard in TerminalView when `inputEnabled`
                    // flips false, so a brief Running window during
                    // step-over can't leave a stale IME open.
                    autoShowKeyboard = state.runState == RunState.Running ||
                        (state.debuggerState.isActive &&
                            state.debuggerState !is dev.cppide.core.debug.DebuggerState.Stopped),
                    onSelectTab = { onIntent(EditorIntent.SwitchBottomTab(it)) },
                    onClose = { onIntent(EditorIntent.ToggleBottomPanel) },
                    onClearTerminal = { onIntent(EditorIntent.ClearTerminal) },
                    onSendTerminalInput = { onIntent(EditorIntent.SendTerminalInput(it)) },
                    onJumpToProblem = { onIntent(EditorIntent.JumpToDiagnostic(it)) },
                    onToggleVariableExpansion = { ref ->
                        onIntent(EditorIntent.ToggleVariableExpansion(ref))
                    },
                    onChatInputChange = { onIntent(EditorIntent.UpdateChatInput(it)) },
                    onChatSend = { onIntent(EditorIntent.SendChatMessage) },
                )
            } else {
                CollapsedPanelBar(
                    chatUnreadCount = state.chatState.unreadCount,
                    isCodeFile = isCodeFile,
                    onSelectTab = { tab ->
                        onIntent(EditorIntent.SwitchBottomTab(tab))
                    },
                )
            }
        }

        // Modal drawer overlay.
        if (state.drawerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { onIntent(EditorIntent.CloseDrawer) },
            )
            // Build the set of file paths with unread chat messages.
            val chatUnreadPaths = if (state.chatState.unreadCount > 0) {
                val openPath = state.openFile?.relativePath
                if (openPath != null) setOf(openPath) else emptySet()
            } else emptySet()

            FileTreeDrawer(
                projectName = state.project.name,
                tree = state.fileTree,
                activePath = state.openFile?.relativePath,
                chatUnreadPaths = chatUnreadPaths,
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

/**
 * Thin bar at the bottom of the editor when the panel is collapsed.
 * Shows tab labels so the user can open any panel tab with one tap —
 * not just via the Run button. Includes a badge dot on Chat if there
 * are unread messages.
 */
@Composable
private fun CollapsedPanelBar(
    chatUnreadCount: Int,
    isCodeFile: Boolean,
    onSelectTab: (BottomPanelTab) -> Unit,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
        CppHorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = dimens.spacingM, vertical = dimens.spacingS),
            horizontalArrangement = Arrangement.spacedBy(dimens.spacingL),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isCodeFile) {
                CollapsedTab("Terminal") { onSelectTab(BottomPanelTab.Terminal) }
                CollapsedTab("Problems") { onSelectTab(BottomPanelTab.Problems) }
                CollapsedTab("Variables") { onSelectTab(BottomPanelTab.Variables) }
            }
            Row(
                modifier = Modifier.clickable { onSelectTab(BottomPanelTab.Chat) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CaptionText(text = "Chat", color = colors.textSecondary)
                if (chatUnreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colors.diagnosticError),
                    )
                }
            }
        }
    }
}

@Composable
private fun CollapsedTab(label: String, onClick: () -> Unit) {
    CaptionText(
        text = label,
        color = CppIde.colors.textSecondary,
        modifier = Modifier.clickable(onClick = onClick),
    )
}
