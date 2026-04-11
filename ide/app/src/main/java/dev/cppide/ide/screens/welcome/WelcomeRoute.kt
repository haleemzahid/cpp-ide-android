package dev.cppide.ide.screens.welcome

import android.util.Log
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
    onChat: () -> Unit,
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
        onChat = onChat,
        onSettings = onSettings,
        onRunDebugSpike = {
            spikeOutput = "Running platform probe…"
            scope.launch {
                core.toolchain.install()
                val result = core.debuggerSpike.runPlatform()
                spikeOutput = formatSpikeResult("platform", result)
            }
        },
        onRunGdbserverSpike = {
            spikeOutput = "Running gdbserver probe…"
            scope.launch {
                core.toolchain.install()
                val result = core.debuggerSpike.runGdbserver()
                spikeOutput = formatSpikeResult("gdbserver", result)
            }
        },
        debugSpikeOutput = spikeOutput,
    )
}

private fun formatSpikeResult(label: String, result: DebuggerSpike.Result): String {
    // Also log the envelope so we can see failures in logcat, not just
    // the UI card. The card may be scrolled off-screen mid-run.
    when (result) {
        is DebuggerSpike.Result.Ok -> Log.i(
            "cppide-dbgspike",
            "$label RESULT Ok banner=${result.banner.replace('\n', '|')}"
        )
        is DebuggerSpike.Result.Failed -> Log.w(
            "cppide-dbgspike",
            "$label RESULT Failed stage=${result.stage} msg=${result.message}"
        )
    }
    return when (result) {
        is DebuggerSpike.Result.Ok ->
            "$label: OK\n\n${result.banner}\n--stderr--\n${result.stderrTail}"
        is DebuggerSpike.Result.Failed ->
            "$label: FAILED [${result.stage}]\n${result.message}\n\n--stderr--\n${result.stderrTail}"
    }
}
