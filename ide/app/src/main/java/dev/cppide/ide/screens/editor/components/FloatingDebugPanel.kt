package dev.cppide.ide.screens.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import dev.cppide.core.debug.DebuggerState
import dev.cppide.ide.components.Codicon
import dev.cppide.ide.components.Codicons
import dev.cppide.ide.screens.editor.EditorIntent
import dev.cppide.ide.theme.CppIde

/**
 * Floating VSCode-style debug toolbar. Renders as an overlay on the
 * editor pane while a debug session is active, anchored top-center.
 *
 * Buttons (matching VSCode's debug toolbar order):
 *
 *   ▶/⏸  Continue / Pause   (toggles based on Running vs Stopped)
 *   ⤵    Step Over
 *   ⬇    Step Into
 *   ⬆    Step Out
 *   ■    Stop
 *
 * Icons are rendered from the bundled Codicons font so they match
 * VSCode's debug toolbar exactly. Disabled state (e.g. Continue while
 * Running) renders as 35% alpha and ignores taps. The whole bar is a
 * dark capsule with a soft shadow so it stays readable on any code
 * background.
 *
 * The host is responsible for visibility — render this only when
 * [debuggerState] is one of [DebuggerState.Starting], [Running],
 * or [Stopped].
 */
@Composable
fun FloatingDebugPanel(
    debuggerState: DebuggerState,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val isStopped = debuggerState is DebuggerState.Stopped
    val isRunning = debuggerState is DebuggerState.Running
    val isActive = isStopped || isRunning || debuggerState is DebuggerState.Starting

    if (!isActive) return

    // Draggable position offset relative to the panel's "natural" anchor
    // (which the host sets via [modifier], typically top-center). Persists
    // for the lifetime of the active debug session — when the session
    // ends the composable leaves the tree and the offset resets.
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // Panel size in px, captured at first layout. Used to clamp the drag
    // so the user can't fling the toolbar entirely off-screen.
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    // Container size — the editor box we live inside. Captured the same
    // way (we use a parent-relative Box modifier in the tree).
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    // Outer Box fills the entire editor area so its size is the
    // real container bounds we clamp the drag against. Caller is
    // expected to pass `Modifier.fillMaxSize()` so this works; we
    // also call .fillMaxSize() defensively in case they don't.
    //
    // Pointer events on empty space inside this Box pass through
    // to the editor underneath (Compose only intercepts touches
    // where there's a registered pointerInput modifier — the inner
    // panel has one, the outer Box does not).
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it },
    ) {
        Box(
            modifier = Modifier
                // Initial position: top-center, with a bit of headroom.
                // Drag offset is added on top.
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                .onSizeChanged { panelSize = it }
                .background(
                    color = Color(0xCC1F1F22),  // dark + slightly translucent
                    shape = RoundedCornerShape(24.dp),
                )
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Drag handle — a slim grip on the left, exactly like
                // VSCode's debug toolbar. Captures pointer input so the
                // user can grab it and reposition the panel anywhere
                // inside the editor area. We constrain the offset so
                // the panel can't be dragged entirely off-screen.
                Box(
                    modifier = Modifier
                        .size(width = 20.dp, height = 36.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val newRaw = dragOffset + dragAmount
                                    // Half-panel padding so at minimum a
                                    // sliver stays visible at every edge,
                                    // matching VSCode's "stays grabbable"
                                    // behavior.
                                    val maxX = (containerSize.width - panelSize.width / 2).toFloat()
                                    val minX = (-panelSize.width / 2).toFloat()
                                    val maxY = (containerSize.height - panelSize.height / 2).toFloat()
                                    val minY = (-panelSize.height / 2).toFloat()
                                    dragOffset = Offset(
                                        x = newRaw.x.coerceIn(minX, maxX),
                                        y = newRaw.y.coerceIn(minY, maxY),
                                    )
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Codicon(
                        char = Codicons.GRIPPER,
                        size = 16.sp,
                        color = Color(0xFF8E8E90),
                    )
                }
            // Continue / Pause toggle. VSCode shows Continue when stopped
            // and Pause when running. Same physical button position, so
            // muscle memory works.
            if (isRunning) {
                ToolbarButton(
                    icon = Codicons.DEBUG_PAUSE,
                    contentColor = Color(0xFFE6E6E6),
                    onClick = { onIntent(EditorIntent.DebugPause) },
                )
            } else {
                ToolbarButton(
                    icon = Codicons.DEBUG_CONTINUE,
                    contentColor = if (isStopped) Color(0xFF89D185) else Color(0xFFE6E6E6),
                    enabled = isStopped,
                    onClick = { onIntent(EditorIntent.DebugContinue) },
                )
            }

            ToolbarButton(
                icon = Codicons.DEBUG_STEP_OVER,
                enabled = isStopped,
                onClick = { onIntent(EditorIntent.DebugStepOver) },
            )
            ToolbarButton(
                icon = Codicons.DEBUG_STEP_INTO,
                enabled = isStopped,
                onClick = { onIntent(EditorIntent.DebugStepInto) },
            )
            ToolbarButton(
                icon = Codicons.DEBUG_STEP_OUT,
                enabled = isStopped,
                onClick = { onIntent(EditorIntent.DebugStepOut) },
            )
            ToolbarButton(
                icon = Codicons.DEBUG_STOP,
                contentColor = Color(0xFFF14C4C),
                onClick = { onIntent(EditorIntent.DebugStop) },
            )
            }  // Row
        }  // inner panel Box
    }  // outer container Box
}

@Composable
private fun ToolbarButton(
    icon: Char,
    onClick: () -> Unit,
    enabled: Boolean = true,
    contentColor: Color = Color(0xFFE6E6E6),
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, radius = 18.dp),
                enabled = enabled,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) {
        Codicon(
            char = icon,
            color = contentColor,
            size = 18.sp,
        )
    }
}
