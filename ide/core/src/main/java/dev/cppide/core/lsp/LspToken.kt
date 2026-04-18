package dev.cppide.core.lsp

/**
 * One semantic-token hit from clangd, decoded out of the delta-encoded
 * wire format into ordinary absolute coordinates. All values are
 * 0-indexed to match LSP.
 *
 * We surface only the fields the debug-inline-values feature needs:
 * where the token sits, how long it is, and what *kind* of token it
 * is (resolved against the server's legend to a human-readable name
 * like "variable" or "parameter").
 */
data class LspToken(
    val line: Int,
    val startChar: Int,
    val length: Int,
    val type: String,
)
