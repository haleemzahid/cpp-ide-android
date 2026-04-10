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
        val pid: Int,
        val pc: Long,
        val reason: StopReason,
        val signal: Int,
        val threadId: String,
    ) : DebuggerState()
    data class Exited(val code: Int, val signaled: Boolean) : DebuggerState()
    data class Failed(val message: String) : DebuggerState()

    val isActive: Boolean
        get() = this is Starting || this is Running || this is Stopped
}

/**
 * Canonical stop reasons from lldb's `T` packet `reason:` key. Mapped
 * from the strings lldb-server sends: `breakpoint`, `trace`, `trap`,
 * `signal`, `watchpoint`, `exception`. Anything else falls back to
 * [UNKNOWN] — we log the raw string in that case.
 */
enum class StopReason {
    BREAKPOINT,
    TRACE,          // single-step completed
    TRAP,           // user interrupt (we sent Ctrl-C)
    SIGNAL,         // target received a real signal (SIGSEGV etc.)
    WATCHPOINT,
    EXCEPTION,
    UNKNOWN,
}
