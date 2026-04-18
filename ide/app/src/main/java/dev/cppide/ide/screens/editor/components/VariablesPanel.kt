package dev.cppide.ide.screens.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cppide.core.debug.DebuggerState
import dev.cppide.core.debug.Scope
import dev.cppide.core.debug.Variable
import dev.cppide.ide.components.BodyText
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.theme.CppIde

/**
 * Bottom-panel "Variables" tab. Shows the variables for the topmost
 * stack frame of the current stop, grouped by scope (Locals,
 * Arguments, Globals, Registers, etc.).
 *
 * UI model:
 *
 *   ▼ Locals
 *       i = 3                int
 *       ▶ vec = {size=5}     std::vector<int>
 *       total = 42           int
 *   ▶ Globals
 *
 * Tap a scope or a structured variable to expand/collapse. Children
 * are fetched lazily on first expand via the
 * [EditorIntent.ToggleVariableExpansion] intent.
 *
 * When the debugger isn't stopped, the panel shows a hint instead.
 */
@Composable
fun VariablesPanel(
    debuggerState: DebuggerState,
    scopes: List<Scope>,
    variables: Map<Int, List<Variable>>,
    expanded: Set<Int>,
    onToggleExpand: (variablesReference: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface),
    ) {
        if (debuggerState !is DebuggerState.Stopped) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CaptionText(
                    text = if (debuggerState is DebuggerState.Idle)
                        "Start debugging to inspect variables."
                    else
                        "Variables appear when the debugger is stopped.",
                    color = colors.textSecondary,
                )
            }
            return@Box
        }

        if (scopes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CaptionText(
                    text = "Loading variables…",
                    color = colors.textSecondary,
                )
            }
            return@Box
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
            scopes.forEach { scope ->
                item(key = "scope-${scope.variablesReference}") {
                    ScopeRow(
                        scope = scope,
                        isExpanded = scope.variablesReference in expanded,
                        onClick = { onToggleExpand(scope.variablesReference) },
                    )
                }
                if (scope.variablesReference in expanded) {
                    val vars = variables[scope.variablesReference] ?: emptyList()
                    items(
                        items = vars,
                        key = { v -> "var-${scope.variablesReference}-${v.name}" },
                    ) { variable ->
                        VariableRow(
                            variable = variable,
                            depth = 1,
                            expanded = expanded,
                            children = variables,
                            onToggleExpand = onToggleExpand,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeRow(
    scope: Scope,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.spacingS, vertical = dimens.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DisclosureTriangle(expanded = isExpanded)
        BodyText(
            text = scope.name,
            color = colors.textPrimary,
        )
        if (scope.expensive) {
            CaptionText(
                text = "(expensive)",
                color = colors.textDisabled,
            )
        }
    }
}

@Composable
private fun VariableRow(
    variable: Variable,
    depth: Int,
    expanded: Set<Int>,
    children: Map<Int, List<Variable>>,
    onToggleExpand: (Int) -> Unit,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val hasChildren = variable.variablesReference > 0
    val isOpen = hasChildren && variable.variablesReference in expanded

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (hasChildren) Modifier.clickable { onToggleExpand(variable.variablesReference) }
                    else Modifier
                )
                .padding(
                    start = (dimens.spacingS.value + (depth * 16f)).dp,
                    end = dimens.spacingS,
                    top = 2.dp,
                    bottom = 2.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (hasChildren) {
                DisclosureTriangle(expanded = isOpen)
            } else {
                Box(modifier = Modifier.size(12.dp))  // pad
            }
            // name
            BodyText(
                text = variable.name,
                color = colors.syntaxVariable,
            )
            // = value
            BodyText(
                text = "=",
                color = colors.textDisabled,
            )
            BodyText(
                text = variable.value,
                color = colors.syntaxString,
            )
            // optional type, dimmed
            variable.type?.let { t ->
                CaptionText(
                    text = t,
                    color = colors.textDisabled,
                )
            }
        }
        if (isOpen) {
            val kids = children[variable.variablesReference] ?: emptyList()
            kids.forEach { child ->
                VariableRow(
                    variable = child,
                    depth = depth + 1,
                    expanded = expanded,
                    children = children,
                    onToggleExpand = onToggleExpand,
                )
            }
        }
    }
}

/** A simple ▶ / ▼ disclosure indicator. Uses text glyphs so we don't
 *  need to ship more icons just for this. */
@Composable
private fun DisclosureTriangle(expanded: Boolean) {
    val colors = CppIde.colors
    androidx.compose.material3.Text(
        text = if (expanded) "▼" else "▶",
        color = colors.textSecondary,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.size(12.dp).padding(end = 2.dp),
    )
}
