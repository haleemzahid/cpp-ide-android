package dev.cppide.core.debug

/**
 * Snapshot of what the debugger is doing, exposed as a StateFlow from
 * [DebuggerService]. Sealed so UI can exhaustively pattern-match without
 * worrying about silent new states being added.
 *
 * State machine:
 *
 *     Idle
 *       │ start(user.so)
 *       ▼
 *     Starting("launching lldb-server")
 *       │ "gdbserver listening"
 *       ▼
 *     Starting("connecting")
 *       │ handshake done, initial stop
 *       ▼
 *     Stopped ←──────────┐
 *       │ cont/step      │
 *       ▼                │
 *     Running            │ next stop packet arrives
 *       │                │
 *       └────────────────┘
 *       │ W/X packet
 *       ▼
 *     Exited
 *
 * Any fatal error transitions to Failed from any state.
 */
sealed class DebuggerState {
    data object Idle : DebuggerState()
    data class Starting(val stage: String) : DebuggerState()
    data class Running(val pid: Int) : DebuggerState()
    data class Stopped(
        /**
         * Best-effort process id. DAP doesn't guarantee one, so this
         * may be 0 — the field is kept for compatibility with the old
         * UI; do not use it for lifecycle decisions.
         */
        val pid: Int,
        val reason: StopReason,
        /** DAP-side thread id (not OS tid). Used to re-issue continue/step. */
        val threadId: Int,
        /**
         * Top-of-stack source location, pulled from the first frame in
         * [callStack]. Null when no debug info is available (e.g. stopped
         * inside libc or the loader).
         */
        val sourceFile: String? = null,
        val sourceLine: Int? = null,
        /** Full call stack, newest frame first. Empty if unavailable. */
        val callStack: List<StackFrame> = emptyList(),
        /**
         * Raw stop description from DAP's `stopped` event (e.g.
         * "breakpoint hit", "step completed", "signal SIGSEGV"). Shown
         * verbatim in the debug panel header.
         */
        val description: String? = null,
    ) : DebuggerState()
    data class Exited(val code: Int, val signaled: Boolean) : DebuggerState()
    data class Failed(val message: String) : DebuggerState()

    val isActive: Boolean
        get() = this is Starting || this is Running || this is Stopped
}

/**
 * Canonical stop reasons. Mapped from DAP's `stopped` event `reason`
 * field, which lldb-dap populates with one of: "breakpoint", "step",
 * "pause", "signal", "exception", "data breakpoint", "exited", or an
 * adapter-specific string. Anything unrecognized becomes [UNKNOWN];
 * the raw string lives in DebuggerState.Stopped.description.
 */
enum class StopReason {
    BREAKPOINT,
    STEP,           // step over/into/out completed
    PAUSE,          // user-initiated interrupt
    SIGNAL,         // target received a signal (SIGSEGV, etc.)
    EXCEPTION,
    ENTRY,          // stopped at program entry (stopOnEntry)
    UNKNOWN,
}
