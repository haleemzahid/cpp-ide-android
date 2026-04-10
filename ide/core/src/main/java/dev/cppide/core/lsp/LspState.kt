package dev.cppide.core.lsp

/**
 * Lifecycle of the embedded clangd language server.
 */
sealed interface LspState {
    /** No project open, no clangd process. */
    data object NotStarted : LspState

    /** clangd subprocess starting up; LSP `initialize` round-trip in flight. */
    data class Starting(val message: String) : LspState

    /**
     * `initialized` notification sent. clangd is parsing the project; first
     * completions / diagnostics may take a few seconds while the index builds.
     */
    data class Ready(val rootPath: String) : LspState

    /** clangd process exited or LSP handshake failed. */
    data class Error(val message: String, val cause: Throwable? = null) : LspState
}
