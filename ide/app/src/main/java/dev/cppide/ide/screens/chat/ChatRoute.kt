package dev.cppide.ide.screens.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.cppide.core.Core

/**
 * Wires [ChatScreen] to a [ChatViewModel] built from [Core]. The VM is
 * `remember`-scoped; the engine it talks to is a process singleton so
 * loaded-model state persists across screen tear-downs.
 */
@Composable
fun ChatRoute(
    core: Core,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel = remember(core) { ChatViewModel(core) }
    val state by viewModel.state.collectAsState()

    ChatScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
    )
}
