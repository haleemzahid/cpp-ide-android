package dev.cppide.ide.screens.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cppide.core.build.Diagnostic
import dev.cppide.core.debug.DebuggerState
import dev.cppide.ide.components.CppHorizontalDivider
import dev.cppide.ide.screens.editor.BottomPanelTab
import dev.cppide.ide.screens.editor.TerminalLine
import dev.cppide.ide.theme.CppIde

/**
 * Container for the editor's bottom panel. Holds the tab strip + active
 * tab content. Fixed height for now (40% of editor area would need a
 * Layout — punting drag-to-resize to v2).
 */
@Composable
fun BottomPanel(
    activeTab: BottomPanelTab,
    terminalLines: List<TerminalLine>,
    problems: List<Diagnostic>,
    debuggerState: DebuggerState,
    onSelectTab: (BottomPanelTab) -> Unit,
    onClose: () -> Unit,
    onClearTerminal: () -> Unit,
    onJumpToProblem: (Diagnostic) -> Unit,
    onStartDebug: () -> Unit,
    onDebugStep: () -> Unit,
    onDebugContinue: () -> Unit,
    onDebugPause: () -> Unit,
    onDebugStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .navigationBarsPadding(),
    ) {
        CppHorizontalDivider()
        BottomPanelTabs(
            activeTab = activeTab,
            problemCount = problems.size,
            onSelectTab = onSelectTab,
            onClose = onClose,
            onClearTerminal = onClearTerminal,
        )
        CppHorizontalDivider()
        Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
            when (activeTab) {
                BottomPanelTab.Terminal -> TerminalView(lines = terminalLines)
                BottomPanelTab.Problems -> ProblemsList(
                    problems = problems,
                    onJumpTo = onJumpToProblem,
                )
                BottomPanelTab.Debug -> DebugPanel(
                    debuggerState = debuggerState,
                    onStart = onStartDebug,
                    onStep = onDebugStep,
                    onContinue = onDebugContinue,
                    onPause = onDebugPause,
                    onStop = onDebugStop,
                )
            }
        }
    }
}
