package dev.cppide.ide.screens.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.screens.editor.OpenFile
import dev.cppide.ide.theme.CppIde

/**
 * Horizontal strip of open editor tabs above the main editor pane.
 * Tapping a tab activates it, tapping the × closes it. Scrolls when
 * more tabs exist than fit on screen.
 */
@Composable
fun EditorTabBar(
    tabs: List<OpenFile>,
    activeIndex: Int?,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    if (tabs.isEmpty()) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(dimens.tabHeight + dimens.spacingS)
            .background(colors.surface),
    ) {
        LazyRow(modifier = Modifier.fillMaxHeight()) {
            itemsIndexed(tabs, key = { _, t -> t.relativePath }) { index, tab ->
                TabChip(
                    tab = tab,
                    active = index == activeIndex,
                    onClick = { onSelect(index) },
                    onClose = { onClose(index) },
                )
            }
        }
        // Bottom hairline so the tab strip doesn't merge into the editor body.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(dimens.borderHairline)
                .background(colors.border),
        )
    }
}

@Composable
private fun TabChip(
    tab: OpenFile,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    val bg = if (active) colors.editorBackground else Color.Transparent
    val fg = if (active) colors.textPrimary else colors.textSecondary

    Row(
        modifier = Modifier
            .fillMaxHeight()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.spacingM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CaptionText(
            text = if (tab.isDirty) "${tab.name} ●" else tab.name,
            color = fg,
        )
        Box(
            modifier = Modifier
                .padding(start = dimens.spacingS)
                .size(18.dp)
                .clip(RoundedCornerShape(dimens.radiusS))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Close tab",
                tint = colors.textSecondary,
                modifier = Modifier.size(12.dp),
            )
        }
        // Active-tab accent bar along the bottom edge.
        if (active) {
            Box(
                modifier = Modifier
                    .size(0.dp)
                    .clip(CircleShape),
            )
        }
    }
}
