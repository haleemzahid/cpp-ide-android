package dev.cppide.ide.screens.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import dev.cppide.ide.screens.editor.RunState
import dev.cppide.ide.theme.CppIde

/**
 * Floating action button for build+run+stop. Shows ▶ when idle, a spinner
 * during install/build, and ⏹ while a program is running. Single-tap
 * fires the right action based on current state.
 */
@Composable
fun RunFab(
    runState: RunState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    Box(
        modifier = modifier
            .shadow(elevation = 6.dp, shape = CircleShape)
            .size(dimens.fabSize)
            .clip(CircleShape)
            .background(colors.accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (runState) {
            RunState.Idle -> Icon(
                imageVector = Icons.Outlined.PlayArrow,
                contentDescription = "Run",
                tint = colors.textOnAccent,
                modifier = Modifier.size(dimens.iconSize),
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
