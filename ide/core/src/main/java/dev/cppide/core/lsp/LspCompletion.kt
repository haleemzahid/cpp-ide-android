package dev.cppide.core.lsp

/**
 * One completion suggestion from clangd. Trimmed down from LSP4J's
 * `CompletionItem` to the fields the editor actually renders.
 */
data class LspCompletion(
    /** What the user sees in the popup. */
    val label: String,
    /** What gets inserted into the buffer if accepted. */
    val insertText: String,
    /** Type info shown alongside the label, e.g. "ostream &" or "(int x)". */
    val detail: String? = null,
    /** Brief docstring or signature. */
    val documentation: String? = null,
    /** Coarse category for icon selection in the UI. */
    val kind: Kind = Kind.OTHER,
    /**
     * Clangd's relevance key — an opaque string we use only to sort the
     * popup so clangd's own ranking wins. Without this, the UI re-sorts
     * by fuzzy score and buries good matches (e.g. `cin` gets shoved
     * below `common_iterator` for prefix "ci").
     */
    val sortText: String? = null,
) {
    enum class Kind {
        VARIABLE, FUNCTION, METHOD, CLASS, STRUCT, ENUM, KEYWORD,
        FIELD, MODULE, FILE, SNIPPET, OTHER,
    }
}
