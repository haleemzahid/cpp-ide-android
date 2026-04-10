package dev.cppide.ide.screens.editor

import dev.cppide.core.build.Diagnostic
import dev.cppide.core.lsp.LspDiagnostic
import java.io.File
import java.net.URI

/**
 * Adapt clangd's [LspDiagnostic] to the [Diagnostic] shape the Problems
 * panel already renders. Two coordinate-system fixes:
 *  - LSP uses 0-indexed line/column; the Problems panel expects 1-indexed
 *  - LSP uses file:// URIs; the panel expects an absolute path
 */
fun LspDiagnostic.toBuildDiagnostic(): Diagnostic {
    val filePath = runCatching { File(URI(fileUri)).absolutePath }.getOrDefault(fileUri)
    val mappedSeverity = when (severity) {
        LspDiagnostic.Severity.ERROR -> Diagnostic.Severity.ERROR
        LspDiagnostic.Severity.WARNING -> Diagnostic.Severity.WARNING
        LspDiagnostic.Severity.INFORMATION -> Diagnostic.Severity.NOTE
        LspDiagnostic.Severity.HINT -> Diagnostic.Severity.NOTE
    }
    return Diagnostic(
        file = filePath,
        line = line + 1,
        column = column + 1,
        severity = mappedSeverity,
        message = message,
    )
}
