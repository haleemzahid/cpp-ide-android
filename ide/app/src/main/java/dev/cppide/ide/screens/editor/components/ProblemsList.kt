package dev.cppide.ide.screens.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cppide.core.build.Diagnostic
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.components.CppHorizontalDivider
import dev.cppide.ide.theme.CppIde

/**
 * Scrollable list of [Diagnostic]s. Shows an empty-state checkmark when
 * the list is empty (last build had no problems).
 */
@Composable
fun ProblemsList(
    problems: List<Diagnostic>,
    onJumpTo: (Diagnostic) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors

    if (problems.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(colors.background),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = colors.textDisabled,
                modifier = Modifier.size(40.dp),
            )
            CaptionText(
                text = "No problems",
                modifier = Modifier.padding(top = CppIde.dimens.spacingS),
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        items(items = problems) { diag ->
            DiagnosticRow(diagnostic = diag, onClick = { onJumpTo(diag) })
            CppHorizontalDivider()
        }
    }
}
