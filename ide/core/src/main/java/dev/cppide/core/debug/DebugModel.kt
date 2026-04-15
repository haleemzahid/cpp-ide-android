package dev.cppide.core.debug

/**
 * Phase-2 debugger data types. These exist because we now drive lldb
 * through DAP, which gives us full stack frames, scopes, and typed
 * variable values — none of which the hand-rolled RSP client could
 * produce.
 *
 * The field set is a loose subset of the DAP spec's corresponding
 * types (DebugProtocol.StackFrame, .Scope, .Variable) — only the
 * fields we actually surface to the user are kept, so we're not
 * over-committing to a wire format that may drift.
 */

/** One frame in the current thread's call stack. */
data class StackFrame(
    /** DAP-assigned frame id, opaque — pass it back to fetch scopes. */
    val id: Int,
    /** Function / symbol name, e.g. "main" or "std::vector<int>::push_back". */
    val name: String,
    /** Source file basename, or null if the frame has no debug info. */
    val sourceFile: String? = null,
    /** 1-indexed line number, or null. */
    val sourceLine: Int? = null,
    /** Program counter in hex ("0x..."), or null. */
    val instructionPointer: String? = null,
)

/**
 * A named variable scope inside a stack frame — typically "Locals",
 * "Arguments", "Globals", "Registers". The `variablesReference` is a
 * DAP handle that you pass to `variables` to fetch the actual entries.
 */
data class Scope(
    val name: String,
    /** DAP handle for the variables request. Zero means "no children". */
    val variablesReference: Int,
    /** True for scopes the UI should start collapsed (globals, registers). */
    val expensive: Boolean = false,
)

/**
 * One entry inside a [Scope] — a local, argument, field, or array
 * element. [variablesReference] is non-zero if the value has children
 * that can be fetched on demand (struct, array, pointer to struct).
 */
data class Variable(
    val name: String,
    val value: String,
    /** Type as a string, e.g. "int", "std::string &", or null if unknown. */
    val type: String? = null,
    /** DAP handle for drilling into children. Zero means "leaf value". */
    val variablesReference: Int = 0,
    /** True if the value looks like a memory reference we could follow. */
    val memoryReference: String? = null,
)
