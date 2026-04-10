package dev.cppide.ide.screens.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.cppide.ide.screens.editor.TerminalLine
import dev.cppide.ide.theme.CppIde

/**
 * Append-only terminal output. Lines are color-coded by their kind:
 *   stdout = primary text
 *   stderr = warning yellow
 *   info   = accent blue
 *   error  = red
 *
 * Auto-scrolls to the latest line as new content arrives.
 */
@Composable
fun TerminalView(
    lines: List<TerminalLine>,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = dimens.spacingM, vertical = dimens.spacingS),
    ) {
        items(items = lines) { line ->
            Text(
                text = line.text.trimEnd('\n'),
                style = CppIde.typography.codeSmall,
                color = line.color(),
            )
        }
    }
}

@Composable
private fun TerminalLine.color(): Color {
    val colors = CppIde.colors
    return when (this) {
        is TerminalLine.Stdout -> colors.textPrimary
        is TerminalLine.Stderr -> colors.diagnosticWarning
        is TerminalLine.Info -> colors.accent
        is TerminalLine.Error -> colors.diagnosticError
    }
}
