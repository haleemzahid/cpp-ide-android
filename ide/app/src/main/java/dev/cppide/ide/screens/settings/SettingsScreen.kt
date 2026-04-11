package dev.cppide.ide.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.cppide.ide.components.CppIconButton
import dev.cppide.ide.components.CppTopBar
import dev.cppide.ide.screens.settings.components.ModelCard
import dev.cppide.ide.theme.CppIde

/**
 * Stateless Settings screen. Renders [SettingsState] and emits
 * [SettingsIntent]s — zero side effects, zero repo access. Wire it to
 * [Core] via [SettingsRoute].
 */
@Composable
fun SettingsScreen(
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        CppTopBar(
            title = "Settings",
            subtitle = "On-device AI models",
            leading = {
                CppIconButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                )
            },
        )

        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = dimens.spacingL,
                end = dimens.spacingL,
                top = dimens.spacingL,
                bottom = dimens.spacingL + bottomInset,
            ),
            verticalArrangement = Arrangement.spacedBy(dimens.spacingM),
        ) {
            items(state.models, key = { it.info.id }) { row ->
                ModelCard(
                    info = row.info,
                    state = row.state,
                    onDownload = { onIntent(SettingsIntent.Download(row.info.id)) },
                    onCancel = { onIntent(SettingsIntent.Cancel(row.info.id)) },
                    onDelete = { onIntent(SettingsIntent.Delete(row.info.id)) },
                )
            }
        }
    }
}
