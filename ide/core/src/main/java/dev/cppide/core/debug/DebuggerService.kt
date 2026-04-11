package dev.cppide.core.debug

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Headless debugger for user-compiled C/C++ code. Wraps lldb-server
 * gdbserver over a localhost GDB-remote connection and exposes an MVI-
 * friendly API: state is observed via [state], commands are suspend
 * functions that return once the target has acknowledged.
 *
 * Threading: every method is `suspend` and is safe to call from any
 * dispatcher. Implementations serialise protocol access internally.
 *
 * Phase 1 scope: start / step / cont / pause / stop. No breakpoints,
 * no source-line mapping, no variables.
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
     * Launch `trampoline` under lldb-server gdbserver with [userLibrary]
     * as the `.so` to dlopen. On success the target is stopped at the
     * trampoline's entry (inside ld-android.so, before main). The user
     * can then step or continue.
     *
     * [trampolineBinary] must be the file system path to `libTrampoline.so`
     * (as exec'd via the jniLibs name trick). [userLibrary] must be a
     * .so exporting the `run_user_main(argc, argv, out_fd, err_fd)`
     * extern "C" symbol — i.e. the product of the normal Run build with
     * the runtime shim.
     */
    suspend fun start(trampolineBinary: File, userLibrary: File): Result<Unit>

    /**
     * Toggle a source-line breakpoint. Adds it to [breakpoints] if not
     * present, removes if present. If the debugger is running, sends
     * the matching `$Z0` / `$z0` packet and updates the entry's
     * verification state. If the debugger is idle, the breakpoint is
     * recorded and will be applied at the start of the next session.
     */
    suspend fun toggleBreakpoint(file: String, line: Int)

    /** Remove all breakpoints. Fires off $z0 for each live one. */
    suspend fun clearBreakpoints()

    /** Single-step one instruction on the current thread. Target must be stopped. */
    suspend fun stepInstruction()

    /** Continue execution until next stop (breakpoint, signal, or exit). */
    suspend fun cont()

    /** Send an async interrupt (Ctrl-C equivalent) while the target is running. */
    suspend fun pause()

    /** Kill the inferior and tear down lldb-server. Idempotent. */
    suspend fun stop()
}
