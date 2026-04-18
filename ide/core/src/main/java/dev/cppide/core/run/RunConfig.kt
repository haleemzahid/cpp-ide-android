package dev.cppide.core.run

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * What to execute in a [RunService.run] invocation.
 *
 * Today the only supported mode is: dlopen a PIC shared library containing
 * a `run_user_main(int, char**, int, int, int)` entry point (the runtime
 * shim) and invoke it with pipe file descriptors for stdin / stdout / stderr.
 */
data class RunConfig(
    /** Absolute path to a .so built with `wrapMain=true`. */
    val library: File,

    /** Command-line arguments passed to user main. argv[0] is synthesised. */
    val args: List<String> = emptyList(),

    /** Program name shown as argv[0] inside user code. Default matches the file name. */
    val programName: String? = null,

    /**
     * Optional stream of user input chunks fed into the inferior's stdin.
     * Each emitted string is written verbatim to the stdin pipe's write-end,
     * so callers are responsible for appending a trailing newline if they
     * want `std::getline` / `cin >> x` to unblock.
     *
     * When null, the inferior's stdin stays closed: `cin` returns EOF on
     * the first read. The flow is collected while the run is active and
     * cancelled when user main returns.
     */
    val stdin: Flow<String>? = null,
)
