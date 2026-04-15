package dev.cppide.ide.screens.editor.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
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
import dev.cppide.core.debug.Scope
import dev.cppide.core.debug.Variable
import dev.cppide.ide.components.CppHorizontalDivider
import dev.cppide.ide.screens.editor.BottomPanelTab
import dev.cppide.ide.screens.editor.ChatPanelState
import dev.cppide.ide.screens.editor.TerminalLine
import dev.cppide.ide.theme.CppIde

@Composable
fun BottomPanel(
    activeTab: BottomPanelTab,
    terminalLines: List<TerminalLine>,
    problems: List<Diagnostic>,
    debuggerState: DebuggerState,
    debugScopes: List<Scope>,
    debugVariables: Map<Int, List<Variable>>,
    expandedVariableRefs: Set<Int>,
    chatState: ChatPanelState,
    isCppFile: Boolean,
    onSelectTab: (BottomPanelTab) -> Unit,
    onClose: () -> Unit,
    onClearTerminal: () -> Unit,
    onJumpToProblem: (Diagnostic) -> Unit,
    onToggleVariableExpansion: (Int) -> Unit,
    onChatInputChange: (String) -> Unit,
    onChatSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val contentHeight = if (activeTab == BottomPanelTab.Chat) 360.dp else 220.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .navigationBarsPadding()
            .animateContentSize(animationSpec = tween(200)),
    ) {
        CppHorizontalDivider()
        BottomPanelTabs(
            activeTab = activeTab,
            problemCount = problems.size,
            chatUnreadCount = chatState.unreadCount,
            isCodeFile = isCppFile,
            onSelectTab = onSelectTab,
            onClose = onClose,
            onClearTerminal = onClearTerminal,
        )
        CppHorizontalDivider()
        Box(modifier = Modifier.fillMaxWidth().height(contentHeight)) {
            when (activeTab) {
                BottomPanelTab.Terminal -> TerminalView(lines = terminalLines)
                BottomPanelTab.Problems -> ProblemsList(
                    problems = problems,
                    onJumpTo = onJumpToProblem,
                )
                BottomPanelTab.Variables -> VariablesPanel(
                    debuggerState = debuggerState,
                    scopes = debugScopes,
                    variables = debugVariables,
                    expanded = expandedVariableRefs,
                    onToggleExpand = onToggleVariableExpansion,
                )
                BottomPanelTab.Chat -> ChatPanel(
                    messages = chatState.messages,
                    inputText = chatState.input,
                    isSending = chatState.isSending,
                    isLoading = chatState.isLoading,
                    isCppFile = isCppFile,
                    onInputChange = onChatInputChange,
                    onSend = onChatSend,
                )
            }
        }
    }
}
