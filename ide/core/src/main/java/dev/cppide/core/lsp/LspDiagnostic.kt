package dev.cppide.core.lsp

/**
 * One LSP diagnostic from clangd. Distinct from
 * [dev.cppide.core.build.Diagnostic] because LSP diagnostics arrive
 * out-of-band as the user types, not as a build artifact, and they carry
 * more semantic info (related-information, code, source).
 */
data class LspDiagnostic(
    val fileUri: String,
    val line: Int,           // 0-indexed (LSP convention)
    val column: Int,         // 0-indexed
    val endLine: Int,
    val endColumn: Int,
    val severity: Severity,
    val message: String,
    val source: String? = null,   // "clang", "clangd", "clang-tidy", ...
    val code: String? = null,     // diagnostic id ("missing-include", ...)
) {
    enum class Severity { ERROR, WARNING, INFORMATION, HINT }
}
