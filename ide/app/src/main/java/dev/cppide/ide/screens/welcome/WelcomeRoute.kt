package dev.cppide.ide.screens.welcome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import dev.cppide.core.Core
import dev.cppide.core.session.RecentProject
import kotlinx.coroutines.launch

/**
 * Wires the stateless [WelcomeScreen] to [Core] services. This is the
 * "screen container" pattern: keep [WelcomeScreen] pure (state in,
 * intents out) and put all the side effects in this thin route.
 */
@Composable
fun WelcomeRoute(
    core: Core,
    onOpenProject: (RecentProject) -> Unit,
    onCreateNew: () -> Unit,
    onOpenExercises: () -> Unit,
    onAbout: () -> Unit,
    onChat: () -> Unit,
    onSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val recents by core.sessionRepository
        .recentProjects()
        .collectAsState(initial = emptyList())

    WelcomeScreen(
        recents = recents,
        onOpenProject = { project ->
            scope.launch {
                core.sessionRepository.touch(project.rootPath, project.displayName)
                onOpenProject(project)
            }
        },
        onTogglePin = { project ->
            scope.launch {
                core.sessionRepository.setPinned(project.rootPath, !project.pinned)
            }
        },
        onCreateNew = onCreateNew,
        onOpenExercises = onOpenExercises,
        onAbout = onAbout,
        onChat = onChat,
        onSettings = onSettings,
    )
}
