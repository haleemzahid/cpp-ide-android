package dev.cppide.core.debug

/**
 * A user breakpoint expressed in source-level terms. Carries both the
 * absolute file system path (which DAP requires in `setBreakpoints`)
 * and the basename (used as the display label and as the hash key).
 *
 * Equality is by basename+line: toggling the same logical breakpoint
 * from different code paths should find the existing entry even if
 * the absolute path was spelled slightly differently (e.g. with a
 * trailing slash, symlink, etc.).
 */
data class SourceBreakpoint(
    /** Absolute path, e.g. "/data/user/0/.../project/main.cpp". */
    val filePath: String,
    /** 1-indexed source line as shown in the editor gutter. */
    val line: Int,
) {
    /** Just the basename, e.g. "main.cpp". */
    val fileBasename: String get() = filePath.substringAfterLast('/')

    override fun equals(other: Any?): Boolean =
        other is SourceBreakpoint && other.fileBasename == fileBasename && other.line == line

    override fun hashCode(): Int = 31 * fileBasename.hashCode() + line
}

/**
 * Runtime state of a single user breakpoint. [verified] becomes true
 * once the debugger confirms the source line resolved to a real address
 * (DAP's `setBreakpoints` response carries a `verified` flag per entry).
 * Unverified breakpoints are shown as hollow markers in the gutter.
 */
data class BreakpointState(
    val source: SourceBreakpoint,
    val verified: Boolean = false,
    /**
     * Free-form message from the debugger when verification fails,
     * e.g. "No executable code at this line" or "File not found in
     * debug info". Shown as a tooltip on hollow markers.
     */
    val message: String? = null,
) {
    val line: Int get() = source.line
}
