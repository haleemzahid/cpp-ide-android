package dev.cppide.core.debug

/**
 * A user breakpoint expressed in source-level terms. The debugger is
 * responsible for resolving this to an instruction address via [SourceMap]
 * and sending `$Z0,addr,4#cc` to lldb-server when the session is active.
 *
 * Only file and line are part of equality so toggling by tap is O(1).
 */
data class SourceBreakpoint(
    /** Source file basename, e.g. "main.cpp". Not an absolute path — we
     *  match against the DWARF file table's basename. */
    val fileBasename: String,
    /** 1-indexed source line as shown in the editor gutter. */
    val line: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is SourceBreakpoint && other.fileBasename == fileBasename && other.line == line

    override fun hashCode(): Int = 31 * fileBasename.hashCode() + line
}

/**
 * Runtime state of a single user breakpoint. [runtimeAddress] is non-null
 * once the debugger has resolved the source line to an actual in-memory
 * address via `libuser.so's DWARF + the .so load base` and successfully
 * set it with `Z0`. If resolution fails (line not in debug info, .so not
 * loaded yet, etc.) it stays null and the UI shows the breakpoint as
 * "unverified" (hollow marker).
 */
data class BreakpointState(
    val source: SourceBreakpoint,
    val runtimeAddress: Long? = null,
    val verified: Boolean = false,
) {
    val line: Int get() = source.line
}
