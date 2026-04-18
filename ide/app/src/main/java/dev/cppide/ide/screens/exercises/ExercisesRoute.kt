package dev.cppide.ide.screens.exercises

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.cppide.core.Core
import dev.cppide.core.project.Project
import kotlinx.coroutines.flow.collect

/**
 * Wires [ExercisesScreen] to [ExercisesViewModel] and forwards
 * download-complete events up so the host can push the editor on top.
 */
@Composable
fun ExercisesRoute(
    core: Core,
    onBack: () -> Unit,
    onOpenProject: (Project) -> Unit,
    onLogin: () -> Unit,
) {
    val viewModel = remember(core) { ExercisesViewModel(core) }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.openProject.collect { project -> onOpenProject(project) }
    }
    LaunchedEffect(viewModel) {
        viewModel.requireLogin.collect { onLogin() }
    }
    // Re-probe the on-disk catalog every time the screen mounts —
    // the user may have deleted a downloaded project from the Welcome
    // screen since they last visited, and the status pill needs to
    // flip back from "Open" to "Download".
    LaunchedEffect(Unit) { viewModel.refresh() }

    ExercisesScreen(
        state = state,
        onBack = onBack,
        onDownload = viewModel::downloadCategory,
        onOpen = viewModel::openDownloaded,
    )
}
