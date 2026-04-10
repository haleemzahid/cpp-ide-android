package dev.cppide.core.run

import java.io.File

/**
 * What to execute in a [RunService.run] invocation.
 *
 * Today the only supported mode is: dlopen a PIC shared library containing
 * a `run_user_main(int, char**, int, int)` entry point (the runtime shim)
 * and invoke it with a pair of pipe file descriptors for stdout/stderr.
 */
data class RunConfig(
    /** Absolute path to a .so built with `wrapMain=true`. */
    val library: File,

    /** Command-line arguments passed to user main. argv[0] is synthesised. */
    val args: List<String> = emptyList(),

    /** Program name shown as argv[0] inside user code. Default matches the file name. */
    val programName: String? = null,
)
