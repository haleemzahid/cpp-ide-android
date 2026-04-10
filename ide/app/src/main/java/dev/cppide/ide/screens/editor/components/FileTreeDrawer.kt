package dev.cppide.ide.screens.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.cppide.core.project.ProjectNode
import dev.cppide.ide.components.CppHorizontalDivider
import dev.cppide.ide.components.SectionText
import dev.cppide.ide.theme.CppIde

/**
 * Left drawer holding the project file tree. Stateless — receives the
 * tree to render and forwards file taps to the caller.
 */
@Composable
fun FileTreeDrawer(
    projectName: String,
    tree: ProjectNode.Directory?,
    activePath: String?,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    Column(
        modifier = modifier
            .width(dimens.drawerWidth)
            .fillMaxHeight()
            .background(colors.sidebar)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        SectionText(
            text = projectName,
            modifier = Modifier.padding(
                horizontal = dimens.spacingL,
                vertical = dimens.spacingM,
            ),
        )
        CppHorizontalDivider()

        if (tree == null) {
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxHeight()) {
            item {
                FileTreeNode(
                    node = tree,
                    depth = 0,
                    activePath = activePath,
                    onFileClick = onFileClick,
                )
            }
        }
    }
}
