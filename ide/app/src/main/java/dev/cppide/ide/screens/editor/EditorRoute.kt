package dev.cppide.ide.screens.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.cppide.core.Core
import dev.cppide.core.project.Project

/**
 * Wires [Core] + a [Project] into [EditorViewModel] + [EditorScreen]. The
 * ViewModel is remember-scoped to the project root so navigating back and
 * forward to the same project keeps state, and switching projects creates
 * a fresh VM with a fresh state.
 */
@Composable
fun EditorRoute(
    core: Core,
    project: Project,
    onBack: () -> Unit,
) {
    val viewModel = remember(project.root.absolutePath) {
        EditorViewModel(core, project)
    }
    val state by viewModel.state.collectAsState()

    EditorScreen(
        state = state,
        onIntent = viewModel::handle,
        onBack = onBack,
        onRequestCompletion = viewModel::requestCompletion,
    )
}
