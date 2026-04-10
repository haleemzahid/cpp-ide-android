package dev.cppide.core.lsp

import dev.cppide.core.project.Project
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Headless LSP client that talks to clangd over JSON-RPC. The UI binds to
 * [state] and [diagnostics]; everything else is fire-and-forget intents.
 *
 * Lifecycle:
 *   start(project)             — spawn clangd, send initialize, become Ready
 *   didOpen(file, content)     — when a file appears in the editor
 *   didChange(file, content)   — debounced (~200ms) on every keystroke
 *   didSave(file)              — once on save (lets clangd reparse)
 *   didClose(file)             — when a tab closes
 *   complete(file, line, col)  — request completions at a cursor position
 *   stop()                     — kill clangd; called when project closes
 *
 * The implementation is lifecycle-agnostic — start/stop can be called
 * multiple times. Implementations must be thread-safe.
 */
interface LspService {

    val state: StateFlow<LspState>

    /**
     * Per-file diagnostics keyed by absolute file path. Updated whenever
     * clangd sends a `textDocument/publishDiagnostics` notification.
     */
    val diagnostics: StateFlow<Map<String, List<LspDiagnostic>>>

    suspend fun start(project: Project): Result<Unit>

    suspend fun stop()

    suspend fun didOpen(file: File, languageId: String, content: String)

    suspend fun didChange(file: File, content: String, version: Int)

    suspend fun didSave(file: File)

    suspend fun didClose(file: File)

    /**
     * Returns the completion list at the given (0-indexed) line/character.
     * Empty list if clangd isn't ready or returned no items.
     */
    suspend fun complete(file: File, line: Int, character: Int): List<LspCompletion>

    /** Hover info at a position, or null. */
    suspend fun hover(file: File, line: Int, character: Int): String?
}
