package dev.cppide.ide.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.cppide.core.Core

/**
 * Wires the stateless [SettingsScreen] to [Core]. The ViewModel is
 * remembered across recompositions; downloads run on the repo's
 * app-scoped coroutine so the actual transfer is unaffected by this
 * screen being torn down.
 */
@Composable
fun SettingsRoute(
    core: Core,
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val viewModel = remember(core) { SettingsViewModel(core = core, appContext = appContext) }
    val state by viewModel.state.collectAsState()

    SettingsScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onBack = onBack,
        onOpenAbout = onOpenAbout,
    )
}
