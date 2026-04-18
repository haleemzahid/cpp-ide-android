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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
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

private val TOP_PADDING = 8.dp

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

    val context = LocalContext.current
    val density = LocalDensity.current
    val topPaddingPx = with(density) { TOP_PADDING.toPx() }

    var dragOffset by remember {
        val p = FloatingDebugPanelPrefs.load(context)
        mutableStateOf(Offset(p.dx, p.dy))
    }
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Re-clamp on container / panel resize (rotation, bottom-panel
    // open/close) so a position saved under one layout doesn't leave
    // the toolbar stranded off-screen under another.
    LaunchedEffect(containerSize, panelSize, topPaddingPx) {
        if (containerSize.width > 0 && panelSize.width > 0) {
            val clamped = clampDragOffset(dragOffset, containerSize, panelSize, topPaddingPx)
            if (clamped != dragOffset) dragOffset = clamped
        }
    }

    // Outer Box fills the editor area to capture container bounds for
    // drag clamping. Empty-space touches fall through to the editor
    // below because only the inner draggable panel has pointerInput.
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = TOP_PADDING)
                .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                .onSizeChanged { panelSize = it }
                .background(
                    color = colors.surfaceElevated.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(24.dp),
                )
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Drag handle: only this region accepts drag gestures,
                // so taps on the toolbar buttons themselves still
                // route to their onClick handlers instead of being
                // swallowed by the drag detector.
                Box(
                    modifier = Modifier
                        .size(width = 20.dp, height = 36.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffset = clampDragOffset(
                                        dragOffset + dragAmount,
                                        containerSize,
                                        panelSize,
                                        topPaddingPx,
                                    )
                                },
                                onDragEnd = {
                                    FloatingDebugPanelPrefs.save(
                                        context,
                                        FloatingDebugPanelPrefs.Position(dragOffset.x, dragOffset.y),
                                    )
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Codicon(
                        char = Codicons.GRIPPER,
                        size = 16.sp,
                        color = colors.textDisabled,
                    )
                }
            // Continue ↔ Pause toggle occupies the same slot, matching
            // VSCode so muscle memory works.
            if (isRunning) {
                ToolbarButton(
                    icon = Codicons.DEBUG_PAUSE,
                    onClick = { onIntent(EditorIntent.DebugPause) },
                )
            } else {
                ToolbarButton(
                    icon = Codicons.DEBUG_CONTINUE,
                    contentColor = if (isStopped) colors.accent else colors.textPrimary,
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
                contentColor = colors.diagnosticError,
                onClick = { onIntent(EditorIntent.DebugStop) },
            )
            }
        }
    }
}

/**
 * Keeps the toolbar fully inside [containerSize]. [dragOffset] is the
 * delta from the panel's natural anchor (top-center, [topPaddingPx]
 * from the top); we convert to absolute, clamp, convert back.
 * Returns the input unchanged on the first layout pass when either
 * size is zero — clamping then would collapse everything to (0,0).
 */
private fun clampDragOffset(
    dragOffset: Offset,
    containerSize: IntSize,
    panelSize: IntSize,
    topPaddingPx: Float,
): Offset {
    if (containerSize.width <= 0 || containerSize.height <= 0) return dragOffset
    if (panelSize.width <= 0 || panelSize.height <= 0) return dragOffset

    val anchorX = (containerSize.width - panelSize.width) / 2f
    val anchorY = topPaddingPx

    val maxX = (containerSize.width - panelSize.width).toFloat().coerceAtLeast(0f)
    val maxY = (containerSize.height - panelSize.height).toFloat().coerceAtLeast(0f)

    val clampedX = (anchorX + dragOffset.x).coerceIn(0f, maxX)
    val clampedY = (anchorY + dragOffset.y).coerceIn(0f, maxY)

    return Offset(clampedX - anchorX, clampedY - anchorY)
}

@Composable
private fun ToolbarButton(
    icon: Char,
    onClick: () -> Unit,
    enabled: Boolean = true,
    contentColor: Color = CppIde.colors.textPrimary,
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
