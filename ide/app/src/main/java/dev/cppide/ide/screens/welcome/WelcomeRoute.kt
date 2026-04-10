package dev.cppide.ide.screens.welcome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.cppide.core.Core
import dev.cppide.core.debug.DebuggerSpike
import dev.cppide.core.session.RecentProject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
    onOpenFolder: () -> Unit,
    onSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val recents by core.sessionRepository
        .recentProjects()
        .collectAsState(initial = emptyList())

    // Temporary spike state — lldb-server reachability test.
    var spikeOutput by remember { mutableStateOf<String?>(null) }

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
        onOpenFolder = onOpenFolder,
        onSettings = onSettings,
        onRunDebugSpike = {
            spikeOutput = "Running…"
            scope.launch {
                // Toolchain must be installed before we can locate lldb-server;
                // clangd spike uses the same prerequisite.
                core.toolchain.install()
                val result = core.debuggerSpike.run()
                spikeOutput = when (result) {
                    is DebuggerSpike.Result.Ok ->
                        "OK\n\nbanner:\n${result.banner}\n\nstderr:\n${result.stderrTail}"
                    is DebuggerSpike.Result.Failed ->
                        "FAILED at [${result.stage}]\n${result.message}\n\nstderr:\n${result.stderrTail}"
                }
            }
        },
        debugSpikeOutput = spikeOutput,
    )
}
