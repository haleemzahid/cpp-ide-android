package dev.cppide.ide.screens.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.cppide.core.build.Diagnostic
import dev.cppide.ide.components.BodyText
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.theme.CppIde

/**
 * Single row in the Problems panel. Severity icon + file name + line:col +
 * message. Tap to jump to the offending location.
 */
@Composable
fun DiagnosticRow(
    diagnostic: Diagnostic,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val (icon, tint) = diagnostic.severity.iconAndTint(colors)
    val fileName = diagnostic.file.substringAfterLast('/')

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.spacingL, vertical = dimens.spacingS),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(dimens.spacingM),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = diagnostic.severity.name,
            tint = tint,
            modifier = Modifier.size(dimens.iconSizeSmall),
        )
        Column(modifier = Modifier.weight(1f)) {
            BodyText(text = diagnostic.message, maxLines = 3)
            CaptionText(
                text = "$fileName:${diagnostic.line}:${diagnostic.column}",
                maxLines = 1,
            )
        }
    }
}

private fun Diagnostic.Severity.iconAndTint(
    colors: dev.cppide.ide.theme.CppIdeColors,
): Pair<ImageVector, Color> = when (this) {
    Diagnostic.Severity.ERROR, Diagnostic.Severity.FATAL ->
        Icons.Outlined.Error to colors.diagnosticError
    Diagnostic.Severity.WARNING ->
        Icons.Outlined.Warning to colors.diagnosticWarning
    Diagnostic.Severity.NOTE, Diagnostic.Severity.REMARK ->
        Icons.Outlined.Info to colors.diagnosticInfo
}
