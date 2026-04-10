package dev.cppide.ide.screens.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.cppide.ide.screens.editor.components.BottomPanel
import dev.cppide.ide.screens.editor.components.EditorPane
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
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

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
            EditorTopBar(
                projectName = state.project.name,
                activeFileName = state.openFile?.name,
                isDirty = state.openFile?.isDirty == true,
                onBack = onBack,
                onToggleDrawer = { onIntent(EditorIntent.ToggleDrawer) },
                onSave = { onIntent(EditorIntent.Save) },
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val openFile = state.openFile
                if (openFile == null) {
                    EmptyEditorPane(modifier = Modifier.fillMaxSize())
                } else {
                    EditorPane(
                        content = openFile.content,
                        onContentChange = { onIntent(EditorIntent.EditContent(it)) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (state.bottomPanelVisible) {
                BottomPanel(
                    activeTab = state.bottomPanelTab,
                    terminalLines = state.terminalLines,
                    problems = state.problems,
                    onSelectTab = { onIntent(EditorIntent.SwitchBottomTab(it)) },
                    onClose = { onIntent(EditorIntent.ToggleBottomPanel) },
                    onClearTerminal = { onIntent(EditorIntent.ClearTerminal) },
                    onJumpToProblem = { onIntent(EditorIntent.JumpToDiagnostic(it)) },
                )
            }
        }

        // ---- run/stop FAB (bottom-right) ----
        RunFab(
            runState = state.runState,
            onClick = { onIntent(EditorIntent.RunOrStop) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = dimens.spacingL, bottom = dimens.spacingL)
                .navigationBarsPadding(),
        )

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
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
    }
}
