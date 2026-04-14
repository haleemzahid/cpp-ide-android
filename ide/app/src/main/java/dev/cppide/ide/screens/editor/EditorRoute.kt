package dev.cppide.ide.screens.editor

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.cppide.core.Core
import dev.cppide.core.project.Project
import kotlinx.coroutines.flow.collect

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
    initialOpenFile: String? = null,
    initialOpenChat: Boolean = false,
) {
    val viewModel = remember(project.root.absolutePath) {
        EditorViewModel(core, project)
    }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // When coming from Questions screen, open the specific file and chat panel.
    LaunchedEffect(viewModel, initialOpenFile, initialOpenChat) {
        if (initialOpenFile != null) {
            viewModel.handle(EditorIntent.OpenFile(initialOpenFile))
        }
        if (initialOpenChat) {
            viewModel.handle(EditorIntent.SwitchBottomTab(BottomPanelTab.Chat))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is EditorEvent.ShareFile -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, event.fileName)
                        putExtra(Intent.EXTRA_TEXT, event.content)
                    }
                    context.startActivity(
                        Intent.createChooser(send, "Share ${event.fileName}")
                    )
                }
            }
        }
    }

    EditorScreen(
        state = state,
        onIntent = viewModel::handle,
        onBack = onBack,
        onRequestCompletion = viewModel::requestCompletion,
        onRequestHover = viewModel::requestHover,
        onChatOpened = viewModel::loadChatForCurrentFile,
        onChatRefresh = viewModel::refreshChat,
        onCheckUnread = viewModel::checkUnread,
    )
}
