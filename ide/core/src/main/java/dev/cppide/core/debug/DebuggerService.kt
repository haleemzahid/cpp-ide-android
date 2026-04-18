package dev.cppide.core.debug

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Headless debugger for user-compiled C/C++ code. Drives lldb via the
 * Debug Adapter Protocol (DAP) and exposes an MVI-friendly API: state
 * is observed via [state], commands are suspend functions that return
 * once the target has acknowledged.
 *
 * Threading: every method is `suspend` and is safe to call from any
 * dispatcher. Implementations serialise protocol access internally.
 *
 * Feature set: start, source-line breakpoints (verified + unverified),
 * step over/into/out (frame-aware, not instruction-level), continue,
 * pause, stop, call stack, scopes, variable inspection.
 */
interface DebuggerService {

    val state: StateFlow<DebuggerState>

    /**
     * Breakpoints the user has set. Tracked separately from [state] so
     * they survive across start/stop cycles of the debugger (the user's
     * red dots in the editor gutter shouldn't vanish when the inferior
     * exits). Each entry carries whether the debugger has verified it
     * against a real address — unverified means "set before the binary
     * was debug-loaded; will take effect next session".
     */
    val breakpoints: StateFlow<Map<SourceBreakpoint, BreakpointState>>

    /**
     * Inferior stdout + stderr lines as the target runs. Emitted from
     * decoded `O` packets on the GDB-remote stream. Consumers typically
     * pipe these into the terminal view.
     */
    val output: SharedFlow<String>

    /**
     * Launch [trampolineBinary] under lldb-dap with [userLibrary] as
     * the `.so` to dlopen. On success the target runs until it hits
     * the user's wrapped `main` (we set a pending breakpoint on
     * `user_main_fn`); stops outside the project root are silently
     * continued past so the user only sees stops inside their own
     * code.
     *
     * [trampolineBinary] must be the file system path to `libTrampoline.so`
     * (as exec'd via the jniLibs name trick). [userLibrary] must be a
     * `.so` exporting `run_user_main(argc, argv, out_fd, err_fd)` —
     * i.e. the product of the normal Run build with the runtime shim
     * and `-g -O0 -gz=none`. [projectRoot] is the directory whose
     * descendants count as "user code"; stops in any frame whose
     * source file is outside this directory are auto-continued.
     */
    suspend fun start(
        trampolineBinary: File,
        userLibrary: File,
        projectRoot: File,
        /**
         * Optional stream of user stdin chunks. Routed to the inferior via
         * a FIFO whose path is passed as `CPPIDE_STDIN_FIFO` in the env;
         * the trampoline opens it and passes the fd as in_fd to the user's
         * `run_user_main`. lldb never sees the FIFO — that sidesteps the
         * broken `process launch -i` / `target.input-path` paths in LLVM 21.
         */
        stdin: Flow<String>? = null,
    ): Result<Unit>

    /**
     * Toggle a source-line breakpoint. Adds to [breakpoints] if not
     * present, removes if present. If a session is active, the full
     * set of breakpoints for the affected file is re-sent via DAP
     * `setBreakpoints` and each entry's `verified` flag is updated
     * from the response. If idle, the breakpoint is recorded and
     * applied at the start of the next session.
     */
    suspend fun toggleBreakpoint(file: String, line: Int)

    /** Remove all breakpoints. If a session is active, clears lldb-side too. */
    suspend fun clearBreakpoints()

    /** Step over the current source line. Target must be stopped. */
    suspend fun stepOver()

    /** Step into a called function on the current source line. */
    suspend fun stepInto()

    /** Run until the current stack frame returns. */
    suspend fun stepOut()

    /** Continue execution until next stop (breakpoint, signal, or exit). */
    suspend fun cont()

    /** Send an async interrupt while the target is running. */
    suspend fun pause()

    /**
     * Fetch the variable scopes (Locals, Arguments, Globals, Registers)
     * for a stack frame. Use [StackFrame.id] from the current
     * [DebuggerState.Stopped.callStack].
     */
    suspend fun fetchScopes(frameId: Int): Result<List<Scope>>

    /**
     * Fetch the variables for a [Scope.variablesReference] or a
     * structured [Variable.variablesReference]. Recurse on demand
     * as the UI expands tree rows.
     */
    suspend fun fetchVariables(variablesReference: Int): Result<List<Variable>>

    /** Kill the inferior and tear down lldb-dap. Idempotent. */
    suspend fun stop()
}
