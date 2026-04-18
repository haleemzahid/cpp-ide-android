package dev.cppide.ide.screens.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.components.CppIconButton
import dev.cppide.ide.screens.editor.BottomPanelTab
import dev.cppide.ide.theme.CppIde

@Composable
fun BottomPanelTabs(
    activeTab: BottomPanelTab,
    problemCount: Int,
    chatUnreadCount: Int,
    isCodeFile: Boolean,
    onSelectTab: (BottomPanelTab) -> Unit,
    onClose: () -> Unit,
    onClearTerminal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(dimens.tabHeight + dimens.spacingS)
            .background(colors.surface)
            .padding(horizontal = dimens.spacingS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isCodeFile) {
            TabButton(
                label = "Terminal",
                active = activeTab == BottomPanelTab.Terminal,
                onClick = { onSelectTab(BottomPanelTab.Terminal) },
            )
            Spacer(Modifier.width(dimens.spacingS))
            TabButton(
                label = if (problemCount > 0) "Problems  $problemCount" else "Problems",
                active = activeTab == BottomPanelTab.Problems,
                onClick = { onSelectTab(BottomPanelTab.Problems) },
            )
            Spacer(Modifier.width(dimens.spacingS))
            TabButton(
                label = "Variables",
                active = activeTab == BottomPanelTab.Variables,
                onClick = { onSelectTab(BottomPanelTab.Variables) },
            )
            Spacer(Modifier.width(dimens.spacingS))
        }
        TabButton(
            label = "Chat",
            active = activeTab == BottomPanelTab.Chat,
            badgeCount = chatUnreadCount,
            onClick = { onSelectTab(BottomPanelTab.Chat) },
        )
        Spacer(Modifier.weight(1f))
        if (isCodeFile && activeTab == BottomPanelTab.Terminal) {
            CppIconButton(
                icon = Icons.Outlined.DeleteSweep,
                contentDescription = "Clear terminal",
                onClick = onClearTerminal,
            )
        }
        CppIconButton(
            icon = Icons.Outlined.Close,
            contentDescription = "Hide panel",
            onClick = onClose,
        )
    }
}

@Composable
private fun TabButton(
    label: String,
    active: Boolean,
    badgeCount: Int = 0,
    onClick: () -> Unit,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    Row(
        modifier = Modifier
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.spacingM, vertical = dimens.spacingS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CaptionText(
            text = label,
            color = if (active) colors.textPrimary else colors.textSecondary,
        )
        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(colors.diagnosticError),
                contentAlignment = Alignment.Center,
            ) {
                CaptionText(
                    text = if (badgeCount > 9) "9+" else "$badgeCount",
                    color = colors.textOnAccent,
                )
            }
        }
    }
    if (active) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(dimens.borderHairline)
                .background(colors.accent),
        )
    }
}
