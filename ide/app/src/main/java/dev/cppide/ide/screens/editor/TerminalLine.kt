package dev.cppide.ide.screens.editor

/**
 * One row in the terminal panel. Sealed so the renderer can color-code
 * each kind without ad-hoc string parsing.
 */
sealed class TerminalLine {
    abstract val text: String

    /** Plain user-program stdout. */
    data class Stdout(override val text: String) : TerminalLine()

    /** User-program stderr. */
    data class Stderr(override val text: String) : TerminalLine()

    /** IDE-emitted status messages ("Building…", "Process exited 0"). */
    data class Info(override val text: String) : TerminalLine()

    /** IDE-emitted errors ("Toolchain install failed: ..."). */
    data class Error(override val text: String) : TerminalLine()
}
