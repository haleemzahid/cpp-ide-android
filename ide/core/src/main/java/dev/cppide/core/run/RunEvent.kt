package dev.cppide.core.run

/**
 * Streamed events from a running user program. A single [RunService.run]
 * call emits exactly one [Started] followed by any number of output lines,
 * terminated by exactly one [Exited] or [Failed].
 */
sealed interface RunEvent {

    data object Started : RunEvent

    /** A chunk of stdout. May be a full line or a partial buffer. */
    data class Stdout(val text: String) : RunEvent

    /** A chunk of stderr. */
    data class Stderr(val text: String) : RunEvent

    /** Program returned normally with the given exit code. */
    data class Exited(val exitCode: Int, val durationMs: Long) : RunEvent

    /** Couldn't load or invoke the program (dlopen failed, dlsym failed, etc). */
    data class Failed(val message: String, val durationMs: Long) : RunEvent
}
