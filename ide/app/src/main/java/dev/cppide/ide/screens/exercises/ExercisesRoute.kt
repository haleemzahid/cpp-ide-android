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
) {
    val viewModel = remember(core) { ExercisesViewModel(core) }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.openProject.collect { project -> onOpenProject(project) }
    }

    ExercisesScreen(
        state = state,
        onBack = onBack,
        onDownload = viewModel::download,
    )
}
