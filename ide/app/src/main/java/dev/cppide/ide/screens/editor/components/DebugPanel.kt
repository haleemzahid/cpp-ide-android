package dev.cppide.ide.screens.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import dev.cppide.core.debug.DebuggerState
import dev.cppide.core.debug.StopReason
import dev.cppide.ide.components.CppButton
import dev.cppide.ide.components.CppButtonStyle
import dev.cppide.ide.components.CppIconButton
import dev.cppide.ide.theme.CppIde

/**
 * Contents of the "Debug" tab in the bottom panel.
 *
 *  - [DebuggerState.Idle]: a big "Start debugging" button.
 *  - Any active state: a control bar (step / continue / pause / stop)
 *    plus a status line (pc, reason, signal).
 *
 * Callback names map 1:1 to [EditorIntent] debug actions.
 */
@Composable
fun DebugPanel(
    debuggerState: DebuggerState,
    onStart: () -> Unit,
    onStep: () -> Unit,
    onContinue: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface)
            .padding(dimens.spacingM),
    ) {
        when (debuggerState) {
            DebuggerState.Idle -> IdleContent(onStart)
            is DebuggerState.Failed -> FailedContent(debuggerState, onStart)
            is DebuggerState.Exited -> ExitedContent(debuggerState, onStart)
            else -> ActiveContent(
                state = debuggerState,
                onStep = onStep,
                onContinue = onContinue,
                onPause = onPause,
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun IdleContent(onStart: () -> Unit) {
    val dimens = CppIde.dimens
    Column(
        verticalArrangement = Arrangement.spacedBy(dimens.spacingM),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CppIconButton(
            icon = Icons.Filled.BugReport,
            contentDescription = null,
            onClick = {},
            tint = CppIde.colors.textSecondary,
        )
        Text(
            text = "Not debugging",
            color = CppIde.colors.textSecondary,
            fontSize = 12.sp,
        )
        CppButton(
            text = "Start debugging",
            onClick = onStart,
            style = CppButtonStyle.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FailedContent(
    state: DebuggerState.Failed,
    onStart: () -> Unit,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    Column(verticalArrangement = Arrangement.spacedBy(dimens.spacingM)) {
        Text(
            text = "Debugger failed",
            color = colors.diagnosticError,
            fontSize = 13.sp,
        )
        Text(
            text = state.message,
            color = colors.textPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        CppButton(
            text = "Retry",
            onClick = onStart,
            style = CppButtonStyle.Secondary,
        )
    }
}

@Composable
private fun ExitedContent(
    state: DebuggerState.Exited,
    onStart: () -> Unit,
) {
    val dimens = CppIde.dimens
    Column(verticalArrangement = Arrangement.spacedBy(dimens.spacingM)) {
        Text(
            text = if (state.signaled)
                "Exited: killed by signal ${state.code}"
            else
                "Exited with code ${state.code}",
            color = CppIde.colors.textPrimary,
            fontSize = 13.sp,
        )
        CppButton(
            text = "Debug again",
            onClick = onStart,
            style = CppButtonStyle.Primary,
        )
    }
}

@Composable
private fun ActiveContent(
    state: DebuggerState,
    onStep: () -> Unit,
    onContinue: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    val running = state is DebuggerState.Running
    val stopped = state is DebuggerState.Stopped

    Column(verticalArrangement = Arrangement.spacedBy(dimens.spacingM)) {
        // ---- control bar ----
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.spacingM)) {
            CppIconButton(
                icon = Icons.Filled.PlayArrow,
                contentDescription = "Continue",
                onClick = onContinue,
                enabled = stopped,
                tint = if (stopped) colors.accent else colors.textDisabled,
            )
            CppIconButton(
                icon = Icons.Filled.SkipNext,
                contentDescription = "Step",
                onClick = onStep,
                enabled = stopped,
                tint = if (stopped) colors.accent else colors.textDisabled,
            )
            CppIconButton(
                icon = Icons.Filled.Pause,
                contentDescription = "Pause",
                onClick = onPause,
                enabled = running,
                tint = if (running) colors.accent else colors.textDisabled,
            )
            CppIconButton(
                icon = Icons.Filled.Stop,
                contentDescription = "Stop",
                onClick = onStop,
                tint = colors.diagnosticError,
            )
        }

        // ---- status ----
        when (state) {
            is DebuggerState.Starting -> Text(
                text = "starting: ${state.stage}…",
                color = colors.textSecondary,
                fontSize = 12.sp,
            )
            is DebuggerState.Running -> Text(
                text = "running (pid ${state.pid})",
                color = colors.textSecondary,
                fontSize = 12.sp,
            )
            is DebuggerState.Stopped -> Column(
                verticalArrangement = Arrangement.spacedBy(dimens.spacingXs),
            ) {
                Text(
                    text = "⏸ stopped (${state.reason.label})",
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                )
                Text(
                    text = "pc  = 0x${state.pc.toString(16).padStart(16, '0')}",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "tid = ${state.threadId}",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                if (state.signal != 0) {
                    Text(
                        text = "sig = ${state.signal}",
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            else -> Unit
        }
    }
}

private val StopReason.label: String
    get() = when (this) {
        StopReason.BREAKPOINT -> "breakpoint"
        StopReason.TRACE -> "step"
        StopReason.TRAP -> "trap"
        StopReason.SIGNAL -> "signal"
        StopReason.WATCHPOINT -> "watchpoint"
        StopReason.EXCEPTION -> "exception"
        StopReason.UNKNOWN -> "unknown"
    }
