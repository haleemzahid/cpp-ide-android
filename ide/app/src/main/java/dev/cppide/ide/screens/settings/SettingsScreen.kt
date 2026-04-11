package dev.cppide.ide.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import dev.cppide.ide.components.BodyText
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
    onOpenAbout: () -> Unit,
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
            item { AboutRow(onClick = onOpenAbout) }
        }
    }
}

@Composable
private fun AboutRow(onClick: () -> Unit) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val shape = RoundedCornerShape(dimens.radiusM)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(dimens.borderHairline, colors.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.spacingL, vertical = dimens.spacingM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spacingL),
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(dimens.iconSize),
        )
        BodyText(text = "About", modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(dimens.iconSize),
        )
    }
}
