package dev.cppide.core.debug

import android.util.Log
import java.io.File

/**
 * Source ↔ address query facade over a parsed DWARF `.debug_line` table.
 * Built once per debug build output (`libuser-debug.so`) and cached until
 * the next rebuild. All addresses it returns are **link-time** — add the
 * runtime load address of the .so before using them in `$Z0` breakpoint
 * packets or comparing against PCs from stop replies.
 *
 * ## API
 *
 *  - [findAddress(fileBasename, line)] returns the lowest-address `is_stmt`
 *    row whose line number is >= `line` and whose file matches the given
 *    basename. Used to resolve a user breakpoint on `main.cpp:42` to an
 *    actual instruction address.
 *
 *  - [findLocation(address)] is the reverse: given a PC, which `(file, line)`
 *    was it compiled from. Used to highlight the current line when the
 *    inferior stops.
 *
 * ## Matching strategy
 *
 *  The DWARF file table contains absolute-ish paths that depend on how
 *  clang was invoked. Our debug build compiles from the user's project
 *  directory with a relative source path (e.g. `main.cpp`), so we match
 *  on the **basename only** — anything in the table whose filename
 *  (after stripping directories) equals the query basename matches.
 *  libc++ headers also appear in the file table via inlining; those
 *  just won't be asked for via a breakpoint, so they're harmless.
 */
class SourceMap private constructor(
    private val rows: List<DwarfLineParser.LineRow>,
) {

    /**
     * Find the (lowest-address) instruction that corresponds to the given
     * source line. Used when setting a breakpoint.
     *
     *  - Prefers rows with `isStmt=true` (those are DWARF's own "good spot
     *    for a breakpoint" hints; gdb and lldb both do this).
     *  - If the exact line has no row, walks forward to the next row in
     *    the same file with a larger line number. This mirrors gdb's
     *    `break foo.cpp:42` behaviour for blank/dead-stripped lines.
     *  - Returns null if nothing in the file is at or after the line.
     */
    fun findAddress(fileBasename: String, line: Int): Long? {
        val target = fileBasename.substringAfterLast('/').substringAfterLast('\\')
        val candidates = rows.asSequence()
            .filter { it.isStmt }
            .filter {
                it.fileName.substringAfterLast('/')
                    .substringAfterLast('\\') == target
            }
            .filter { it.line >= line }
            .sortedWith(compareBy({ it.line }, { it.address }))
        return candidates.firstOrNull()?.address
    }

    /**
     * Reverse lookup: which source line was this instruction generated
     * from? Returns the last row with address <= [pc] (the state machine
     * walks forward, so the current row covers PCs up to the next row's
     * address). Returns null if [pc] is outside any CU's range.
     */
    fun findLocation(pc: Long): SourceLocation? {
        var best: DwarfLineParser.LineRow? = null
        for (row in rows) {
            if (row.address <= pc) {
                if (best == null || row.address > best.address) best = row
            }
        }
        return best?.let { SourceLocation(it.fileName, it.line, it.column) }
    }

    /** Unique source file basenames that appear in the line table. Useful
     *  for filtering out libc++ headers in the breakpoint UI. */
    val fileBasenames: Set<String> by lazy {
        rows.map {
            it.fileName.substringAfterLast('/').substringAfterLast('\\')
        }.toSet()
    }

    val rowCount: Int get() = rows.size

    companion object {
        private const val TAG = "cppide-sourcemap"

        /**
         * Build a SourceMap for a debug-built .so. Extracts the .debug_line
         * section via [ElfReader], parses with [DwarfLineParser], and
         * wraps the result. Returns null if the file isn't a valid ELF64
         * or doesn't contain DWARF line info (i.e. was built without -g).
         */
        fun fromElf(elf: File): SourceMap? {
            val sectionBytes = runCatching { ElfReader(elf).findSectionBytes(".debug_line") }
                .onFailure { Log.w(TAG, "ELF read failed for ${elf.name}", it) }
                .getOrNull()
                ?: run {
                    Log.w(TAG, "${elf.name}: no .debug_line section")
                    return null
                }
            val rows = runCatching { DwarfLineParser(sectionBytes).parse() }
                .onFailure { Log.w(TAG, "DWARF parse failed for ${elf.name}", it) }
                .getOrNull()
                ?: return null
            Log.i(TAG, "${elf.name}: parsed ${rows.size} line rows")
            return SourceMap(rows)
        }
    }
}

/** Where in source a PC originated. */
data class SourceLocation(
    val file: String,
    val line: Int,
    val column: Int,
)
