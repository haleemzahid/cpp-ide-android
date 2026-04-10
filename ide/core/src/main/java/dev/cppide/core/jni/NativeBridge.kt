package dev.cppide.core.jni

import android.util.Log

/**
 * Thin Kotlin facade over libjnibridge.so. The native methods are defined
 * by `core/src/main/cpp/jnibridge.c` and reached via automatic JNI name
 * resolution. Loaded exactly once by the class initializer.
 *
 * Callers should use the higher-level services ([dev.cppide.core.run.RunService]
 * etc.) rather than this class directly.
 */
object NativeBridge {

    private const val TAG = "cppide-core"

    val isLoaded: Boolean

    init {
        isLoaded = try {
            System.loadLibrary("jnibridge")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "System.loadLibrary(jnibridge) failed", t)
            false
        }
    }

    /**
     * dlopen [libPath], dlsym `run_user_main`, invoke it with pipe fds
     * mapped onto stdout / stderr, return the exit code.
     *
     * The caller must close the write-ends of the pipes after this
     * returns so the reader coroutine sees EOF.
     *
     * Error codes:
     *   -1001  dlopen failed
     *   -1002  dlsym failed
     *   -9999  unexpected exception crossing the JNI boundary
     */
    @JvmStatic
    external fun runUserProgram(libPath: String, stdoutFd: Int, stderrFd: Int): Int

    /**
     * Diagnostic: does [libPath] export [symbol] once dlopen'd? Useful for
     * validating compiler output before committing to a full run.
     */
    @JvmStatic
    external fun hasSymbol(libPath: String, symbol: String): Boolean

    /**
     * Spike 2c primitive: fork+exec a binary with PTRACE_TRACEME, single-step
     * a few instructions, continue, observe exit. Returns a human-readable
     * report string. Keeps the knowledge "we can ptrace on Android 15" visible
     * in the backend so the debugger layer has a working baseline to build on.
     */
    @JvmStatic
    external fun ptraceTest(binaryPath: String): String
}
