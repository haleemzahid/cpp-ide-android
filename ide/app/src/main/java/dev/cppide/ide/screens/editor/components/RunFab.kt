package dev.cppide.ide.screens.editor.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cppide.ide.components.Codicon
import dev.cppide.ide.components.Codicons
import dev.cppide.ide.screens.editor.RunState
import dev.cppide.ide.theme.CppIde

/**
 * The bottom-right action FAB. Has three modes depending on state:
 *
 *  - **Idle**: a green play button. Tapping expands two child FABs
 *    above it — "Run" (no debug) and "Debug" — which slide in with
 *    a small spring + fade animation. The main FAB rotates 45° to
 *    visually signal "menu open"; tapping it again collapses the
 *    menu without firing an action. Tapping a child fires the
 *    matching intent and collapses the menu.
 *
 *  - **Running / building / installing**: collapsed and shows a
 *    spinner or stop icon. Single-tap stops the current run. No
 *    menu — there's no second action available while running.
 *
 *  - **Debugging**: hidden entirely. The floating debug toolbar
 *    over the editor handles all debugger controls; the FAB would
 *    be redundant and the user has the more discoverable controls.
 *
 * Codicons are used for child labels so the icons match VSCode's
 * visual vocabulary (PLAY for run, DEBUG_ALT for debug).
 */
@Composable
fun RunFab(
    runState: RunState,
    onRun: () -> Unit,
    onDebug: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    var expanded by remember { mutableStateOf(false) }

    // Whenever we leave the idle state (a run/debug starts) the menu
    // should snap shut so it doesn't stick around obscuring the UI.
    if (runState != RunState.Idle && expanded) expanded = false

    val rotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = spring(),
        label = "fab-rotation",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.Bottom,
    ) {
        // ---- expanding child buttons ----
        // Order: top-most appears first when expanding (VSCode debug
        // toolbar puts Start to the left of Debug; we put Start on
        // top because it's the more frequent action).
        AnimatedVisibility(
            visible = expanded && runState == RunState.Idle,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }) + scaleIn(),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }) + scaleOut(),
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(dimens.spacingS),
                modifier = Modifier.padding(bottom = dimens.spacingS),
            ) {
                ChildFab(
                    icon = Codicons.PLAY,
                    label = "Run",
                    background = colors.accent,
                    onClick = {
                        expanded = false
                        onRun()
                    },
                )
                ChildFab(
                    icon = Codicons.DEBUG_ALT,
                    label = "Debug",
                    background = Color(0xFFE06C00),
                    onClick = {
                        expanded = false
                        onDebug()
                    },
                )
            }
        }

        // ---- main FAB ----
        Box(
            modifier = Modifier
                .shadow(elevation = 6.dp, shape = CircleShape)
                .size(dimens.fabSize)
                .clip(CircleShape)
                .background(colors.accent)
                .clickable {
                    when (runState) {
                        RunState.Idle -> expanded = !expanded
                        RunState.Running -> onStop()
                        RunState.InstallingToolchain, RunState.Building -> Unit
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when (runState) {
                // `+` rotated 45° becomes `×`, which is the FAB-menu
                // convention for "tap to expand / tap to close". We
                // animate the rotation when toggling, so the icon
                // visibly morphs from + (collapsed) to × (expanded).
                RunState.Idle -> Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = if (expanded) "Close run menu" else "Open run menu",
                    tint = colors.textOnAccent,
                    modifier = Modifier
                        .size(dimens.iconSize)
                        .rotate(rotation),
                )
                RunState.InstallingToolchain, RunState.Building -> CircularProgressIndicator(
                    color = colors.textOnAccent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(dimens.iconSize),
                )
                RunState.Running -> Icon(
                    imageVector = Icons.Outlined.Stop,
                    contentDescription = "Stop",
                    tint = colors.textOnAccent,
                    modifier = Modifier.size(dimens.iconSize),
                )
            }
        }
    }
}

@Composable
private fun ChildFab(
    icon: Char,
    label: String,
    background: Color,
    onClick: () -> Unit,
) {
    val dimens = CppIde.dimens
    Box(
        modifier = Modifier
            .shadow(elevation = 4.dp, shape = CircleShape)
            .size(dimens.fabSize - 8.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Codicon(
            char = icon,
            color = Color.White,
            size = 18.sp,
        )
    }
}
