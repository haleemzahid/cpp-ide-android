package dev.cppide.spike1

import android.content.Context
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The plumbing for Spike 1. Three independent validations:
 *
 *  A. runNativeHelloFromNativeLibraryDir()
 *     Proves we can execve() a binary shipped via jniLibs. This is the
 *     foundational assumption — if Android blocks this, every other plan dies.
 *
 *  B. copyAndRunFromFilesDir()
 *     Proves we can execve() a binary we WROTE to app-private storage after
 *     install. This is how a compiler's output would be run.
 *
 *  C. runClangVersion() / compileAndRunHelloCpp()
 *     Only light up if a user supplies libclang.so (Termux pkg, or a self-built
 *     Android-native clang). Validates the real compiler loop end-to-end.
 */
class SpikeRunner(private val ctx: Context) {

    companion object {
        init {
            // libjnibridge.so lives in nativeLibraryDir and holds the
            // JNI implementations for nativeRunUserProgram / nativeCheckSymbol.
            try {
                System.loadLibrary("jnibridge")
            } catch (t: Throwable) {
                android.util.Log.e("cppide-spike1", "loadLibrary(jnibridge) failed", t)
            }
        }

        @JvmStatic external fun nativeRunUserProgram(libPath: String, outFd: Int, errFd: Int): Int
        @JvmStatic external fun nativeCheckSymbol(libPath: String, symbol: String): Boolean
        @JvmStatic external fun nativePtraceTest(binaryPath: String): String
    }

    data class ExecResult(
        val command: String,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val durationMs: Long,
        val error: String? = null,
    ) {
        // "ran" = we successfully invoked the thing. The exit code is a
        // signal the program itself emits; a non-zero exit still counts as
        // a successful validation of the runtime loop.
        val ran: Boolean get() = error == null
        val cleanExit: Boolean get() = ran && exitCode == 0
    }

    // ---------- Paths ----------

    private val nativeLibDir: File
        get() = File(ctx.applicationInfo.nativeLibraryDir)

    private val workDir: File
        get() = File(ctx.filesDir, "work").apply { mkdirs() }

    val extractor = ToolchainExtractor(ctx)

    val nativeHelloBinary: File
        get() = File(nativeLibDir, "libnativehello.so")

    val helloLib: File
        get() = File(nativeLibDir, "libhellolib.so")

    val userLib: File
        get() = File(nativeLibDir, "libuser.so")

    val debugTarget: File
        get() = File(nativeLibDir, "libdebug_target.so")

    val clangBinary: File
        get() = File(nativeLibDir, "libclang.so")

    // ---------- Status ----------

    fun listNativeLibDir(): List<String> =
        nativeLibDir.listFiles()?.map { "${it.name} (${it.length()} bytes)" } ?: emptyList()

    fun nativeHelloPresent(): Boolean = nativeHelloBinary.exists()
    fun helloLibPresent(): Boolean = helloLib.exists()
    fun userLibPresent(): Boolean = userLib.exists()
    fun debugTargetPresent(): Boolean = debugTarget.exists()
    fun clangPresent(): Boolean = clangBinary.exists()

    // ---------- A. native-hello from nativeLibraryDir ----------

    suspend fun runNativeHelloFromNativeLibraryDir(): ExecResult = withContext(Dispatchers.IO) {
        if (!nativeHelloBinary.exists()) {
            return@withContext ExecResult(
                command = nativeHelloBinary.absolutePath,
                exitCode = -1,
                stdout = "",
                stderr = "",
                durationMs = 0,
                error = "libnativehello.so missing — did buildNativeHello Gradle task run?"
            )
        }
        exec(listOf(nativeHelloBinary.absolutePath, "--from-nativeLibraryDir"))
    }

    // ---------- B. copy to filesDir and exec ----------

    suspend fun copyAndRunFromFilesDir(): ExecResult = withContext(Dispatchers.IO) {
        val src = nativeHelloBinary
        if (!src.exists()) {
            return@withContext ExecResult(
                command = "copy+exec",
                exitCode = -1,
                stdout = "",
                stderr = "",
                durationMs = 0,
                error = "libnativehello.so missing in nativeLibraryDir"
            )
        }
        val dst = File(workDir, "hello_copied")
        try {
            src.copyTo(dst, overwrite = true)
            // chmod 700 — owner rwx
            if (!dst.setExecutable(true, true)) {
                return@withContext ExecResult(
                    command = dst.absolutePath,
                    exitCode = -1,
                    stdout = "",
                    stderr = "",
                    durationMs = 0,
                    error = "setExecutable(true) returned false on ${dst.absolutePath}"
                )
            }
        } catch (e: IOException) {
            return@withContext ExecResult(
                command = "copy",
                exitCode = -1,
                stdout = "",
                stderr = "",
                durationMs = 0,
                error = "IOException copying to filesDir: ${e.message}"
            )
        }
        exec(listOf(dst.absolutePath, "--from-filesDir"))
    }

    // ---------- D. baseline dlopen from nativeLibraryDir ----------
    //
    // System.loadLibrary("hellolib") resolves to nativeLibraryDir/libhellolib.so.
    // If this fails, our shared-library build itself is broken.

    suspend fun loadLibraryFromNativeLibDir(): ExecResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        if (!helloLib.exists()) {
            return@withContext ExecResult(
                command = "System.loadLibrary(\"hellolib\")",
                exitCode = -1, stdout = "", stderr = "",
                durationMs = 0,
                error = "libhellolib.so missing — did buildHelloLib Gradle task run?"
            )
        }
        try {
            System.loadLibrary("hellolib")
            ExecResult(
                command = "System.loadLibrary(\"hellolib\") -> $helloLib",
                exitCode = 0,
                stdout = "dlopen succeeded from nativeLibraryDir (JNI_OnLoad ran — check logcat)",
                stderr = "",
                durationMs = System.currentTimeMillis() - started,
            )
        } catch (e: Throwable) {
            ExecResult(
                command = "System.loadLibrary(\"hellolib\")",
                exitCode = -1, stdout = "", stderr = "",
                durationMs = System.currentTimeMillis() - started,
                error = "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    // ---------- E. dlopen from filesDir — THE CRITICAL TEST ----------
    //
    // This is the workaround path for "we compiled user code, now run it."
    // If this works, we can ship clang + compile user programs as .so files
    // + dlopen them via this path. If this also fails, we need LD_PRELOAD
    // or proot.

    suspend fun loadLibraryFromFilesDir(): ExecResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val src = helloLib
        if (!src.exists()) {
            return@withContext ExecResult(
                command = "copy + System.load",
                exitCode = -1, stdout = "", stderr = "",
                durationMs = 0,
                error = "libhellolib.so missing in nativeLibraryDir"
            )
        }
        val dst = File(workDir, "libhellolib_copied.so")
        try {
            src.copyTo(dst, overwrite = true)
        } catch (e: IOException) {
            return@withContext ExecResult(
                command = "copy",
                exitCode = -1, stdout = "", stderr = "",
                durationMs = System.currentTimeMillis() - started,
                error = "IOException copying: ${e.message}"
            )
        }
        try {
            System.load(dst.absolutePath)
            ExecResult(
                command = "System.load(\"${dst.absolutePath}\")",
                exitCode = 0,
                stdout = "dlopen succeeded from filesDir! (Option A viable — compile user code as .so)",
                stderr = "",
                durationMs = System.currentTimeMillis() - started,
            )
        } catch (e: Throwable) {
            ExecResult(
                command = "System.load(\"${dst.absolutePath}\")",
                exitCode = -1, stdout = "", stderr = "",
                durationMs = System.currentTimeMillis() - started,
                error = "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    // ---------- F. THE RUNTIME LOOP: dlopen libuser.so + call main + capture stdout ----------
    //
    // This is the final validation: simulate what the real IDE will do every
    // time the user hits "Run":
    //
    //   1. Copy libuser.so from nativeLibraryDir to filesDir (in production
    //      it would be produced here by on-device clang).
    //   2. Create a pipe via ParcelFileDescriptor.
    //   3. JNI -> dlopen libuser.so from filesDir -> dlsym run_user_main
    //      -> call with pipe write fd mapped to stdout(1).
    //   4. Read the pipe read end into a string — that's the user's printf
    //      output.
    //   5. Return it for display in the UI.

    suspend fun runUserProgramShim(): ExecResult = coroutineScope {
        val started = System.currentTimeMillis()

        val src = userLib
        if (!src.exists()) {
            return@coroutineScope ExecResult(
                command = "runUserProgramShim",
                exitCode = -1, stdout = "", stderr = "",
                durationMs = 0,
                error = "libuser.so missing — did buildUserLib run?"
            )
        }

        // Step 1: copy to filesDir to simulate "compiler wrote this here".
        val dst = File(workDir, "libuser_compiled.so")
        try {
            src.copyTo(dst, overwrite = true)
        } catch (e: IOException) {
            return@coroutineScope ExecResult(
                command = "copy libuser.so -> filesDir",
                exitCode = -1, stdout = "", stderr = "",
                durationMs = System.currentTimeMillis() - started,
                error = "copy failed: ${e.message}"
            )
        }

        // Step 2: pre-flight check that the symbol is resolvable.
        if (!nativeCheckSymbol(dst.absolutePath, "run_user_main")) {
            return@coroutineScope ExecResult(
                command = "dlsym run_user_main",
                exitCode = -1, stdout = "", stderr = "",
                durationMs = System.currentTimeMillis() - started,
                error = "run_user_main symbol not found in ${dst.name}"
            )
        }

        // Step 3: pipe setup.
        val stdoutPipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: IOException) {
            return@coroutineScope ExecResult(
                command = "createPipe(stdout)",
                exitCode = -1, stdout = "", stderr = "",
                durationMs = System.currentTimeMillis() - started,
                error = "createPipe failed: ${e.message}"
            )
        }
        val stderrPipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: IOException) {
            stdoutPipe.forEach { it.close() }
            return@coroutineScope ExecResult(
                command = "createPipe(stderr)",
                exitCode = -1, stdout = "", stderr = "",
                durationMs = System.currentTimeMillis() - started,
                error = "createPipe failed: ${e.message}"
            )
        }
        val stdoutRead = stdoutPipe[0]
        val stdoutWriteFd = stdoutPipe[1].detachFd()
        val stderrRead = stderrPipe[0]
        val stderrWriteFd = stderrPipe[1].detachFd()

        // Step 4: launch readers BEFORE the JNI call so they're already
        // pumping bytes as soon as the user's printf runs.
        val stdoutDeferred = async(Dispatchers.IO) {
            ParcelFileDescriptor.AutoCloseInputStream(stdoutRead).use { it.readBytes().decodeToString() }
        }
        val stderrDeferred = async(Dispatchers.IO) {
            ParcelFileDescriptor.AutoCloseInputStream(stderrRead).use { it.readBytes().decodeToString() }
        }

        // Step 5: the call itself.
        val rc = withContext(Dispatchers.IO) {
            try {
                nativeRunUserProgram(dst.absolutePath, stdoutWriteFd, stderrWriteFd)
            } catch (t: Throwable) {
                -9999
            } finally {
                // Close the write ends so the readers see EOF. adoptFd wraps
                // the raw fd in a PFD that closes it on close().
                try { ParcelFileDescriptor.adoptFd(stdoutWriteFd).close() } catch (_: Throwable) {}
                try { ParcelFileDescriptor.adoptFd(stderrWriteFd).close() } catch (_: Throwable) {}
            }
        }

        val out = stdoutDeferred.await()
        val err = stderrDeferred.await()

        ExecResult(
            command = "dlopen ${dst.name} + run_user_main",
            exitCode = rc,
            stdout = out,
            stderr = err,
            durationMs = System.currentTimeMillis() - started,
        )
    }

    // ---------- I. ptrace spike (Spike 2c) ----------
    //
    // Fork + exec libdebug_target.so with PTRACE_TRACEME, single-step a few
    // instructions, continue to completion, read exit code. If this succeeds
    // on Android 15, the debugger path (lldb helper) is fully unblocked.

    suspend fun runPtraceSpike(): ExecResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        if (!debugTarget.exists()) {
            return@withContext ExecResult(
                command = "nativePtraceTest",
                exitCode = -1, stdout = "", stderr = "",
                durationMs = 0,
                error = "libdebug_target.so missing — did buildDebugTarget run?"
            )
        }
        try {
            val report = nativePtraceTest(debugTarget.absolutePath)
            val ok = report.contains("child exited, code=42")
            ExecResult(
                command = "nativePtraceTest(${debugTarget.name})",
                exitCode = if (ok) 0 else 1,
                stdout = report,
                stderr = "",
                durationMs = System.currentTimeMillis() - started,
            )
        } catch (t: Throwable) {
            ExecResult(
                command = "nativePtraceTest",
                exitCode = -1, stdout = "", stderr = "",
                durationMs = System.currentTimeMillis() - started,
                error = "${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    // ---------- G. Termux toolchain: install + clang --version ----------

    suspend fun installToolchain(progress: (String) -> Unit = {}): ExecResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            val r = extractor.install(progress)
            val msg = if (r.alreadyInstalled) {
                "toolchain already extracted at ${extractor.termuxRoot}"
            } else {
                "extracted ${r.filesExtracted} files, created ${r.symlinksCreated} symlinks in ${r.durationMs} ms"
            }
            ExecResult(
                command = "ToolchainExtractor.install",
                exitCode = 0,
                stdout = msg,
                stderr = "",
                durationMs = System.currentTimeMillis() - started,
            )
        } catch (e: Throwable) {
            ExecResult(
                command = "ToolchainExtractor.install",
                exitCode = -1,
                stdout = "",
                stderr = "",
                durationMs = System.currentTimeMillis() - started,
                error = "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    suspend fun runClangVersion(): ExecResult = withContext(Dispatchers.IO) {
        if (!clangBinary.exists()) {
            return@withContext ExecResult(
                command = "clang --version",
                exitCode = -1,
                stdout = "",
                stderr = "",
                durationMs = 0,
                error = "libclang.so not present in nativeLibraryDir — run stage_toolchain.py and rebuild."
            )
        }
        // Make sure the toolchain is extracted (idempotent).
        try { extractor.install() } catch (_: Throwable) { /* report below */ }
        if (!extractor.isInstalled()) {
            return@withContext ExecResult(
                command = "clang --version",
                exitCode = -1, stdout = "", stderr = "",
                durationMs = 0,
                error = "toolchain not extracted yet — tap 'Install toolchain' first"
            )
        }

        // clang binary is exec'd via its symlink in bin/ so argv[0] is "clang"
        // (clang uses basename(argv[0]) to decide C vs C++ driver mode).
        val clangSymlink = File(extractor.binDir, "clang")
        exec(
            listOf(clangSymlink.absolutePath, "--version"),
            extraEnv = buildToolchainEnv(),
        )
    }

    // ---------- H. On-device compile + run of hello.cpp ----------
    //
    // End-to-end pipeline:
    //   1. Extract hello.cpp + runtime_shim.c from assets -> filesDir/work/
    //   2. Invoke bundled clang++ to compile them into libuser_compiled.so
    //      (shared library, PIC, with -Dmain=user_main_fn so the shim can
    //      call the user's main as a regular function).
    //   3. dlopen the resulting .so from filesDir via the existing JNI
    //      bridge (validated by button F in Spike 2a).
    //   4. Pipe stdout/stderr back to the UI.

    suspend fun compileAndRunHelloCpp(): ExecResult = coroutineScope {
        val started = System.currentTimeMillis()

        // Ensure toolchain is available
        if (!clangBinary.exists()) {
            return@coroutineScope ExecResult(
                command = "compile hello.cpp",
                exitCode = -1, stdout = "", stderr = "",
                durationMs = 0,
                error = "libclang.so not present"
            )
        }
        try { extractor.install() } catch (_: Throwable) {}
        if (!extractor.isInstalled()) {
            return@coroutineScope ExecResult(
                command = "compile hello.cpp",
                exitCode = -1, stdout = "", stderr = "",
                durationMs = 0,
                error = "toolchain not extracted"
            )
        }

        // Step 1: extract sources from assets
        val srcDir = File(workDir, "src").apply { mkdirs() }
        val helloSrc = File(srcDir, "hello.cpp")
        val shimSrc = File(srcDir, "runtime_shim.cpp")
        try {
            ctx.assets.open("hello.cpp").use { it.copyTo(helloSrc.outputStream()) }
            ctx.assets.open("runtime_shim.cpp").use { it.copyTo(shimSrc.outputStream()) }
        } catch (e: Exception) {
            return@coroutineScope ExecResult(
                command = "extract hello.cpp/runtime_shim.cpp",
                exitCode = -1, stdout = "", stderr = "",
                durationMs = System.currentTimeMillis() - started,
                error = "asset copy failed: ${e.message}"
            )
        }

        // Step 2: invoke clang++ to build a .so
        val outSo = File(workDir, "libuser_compiled.so")
        if (outSo.exists()) outSo.delete()

        val clangxx = File(extractor.binDir, "clang++").absolutePath
        val sysroot = extractor.sysroot.absolutePath
        val resourceDir = extractor.clangResDir.absolutePath

        val compileCmd = listOf(
            clangxx,
            "-v",                                   // verbose so we see the internal commands
            "-target", "aarch64-linux-android26",
            "--sysroot=$sysroot",
            "-resource-dir=$resourceDir",
            "-fPIC", "-shared",
            "-O1",
            "-Dmain=user_main_fn",
            // Linker search paths for libc++_shared.so (shipped in nativeLibraryDir)
            // and any versioned libs we extracted to filesDir/termux/lib.
            "-L${nativeLibDir.absolutePath}",
            "-L${extractor.libDir.absolutePath}",
            // Both files compile as C++ so user_main_fn gets the same
            // mangling on both sides of the extern declaration.
            "-x", "c++",
            helloSrc.absolutePath,
            shimSrc.absolutePath,
            "-o", outSo.absolutePath,
        )

        val compileResult = exec(compileCmd, extraEnv = buildToolchainEnv())
        if (!compileResult.ran || compileResult.exitCode != 0) {
            return@coroutineScope compileResult.copy(
                command = "CLANG: ${compileResult.command}\n--- output ---\n" +
                        "stdout: ${compileResult.stdout}\nstderr: ${compileResult.stderr}"
            )
        }
        if (!outSo.exists()) {
            return@coroutineScope ExecResult(
                command = "clang++ compile",
                exitCode = -1,
                stdout = compileResult.stdout,
                stderr = compileResult.stderr,
                durationMs = System.currentTimeMillis() - started,
                error = "clang returned 0 but output file missing"
            )
        }

        // Step 3: dlopen + call via JNI bridge (same as button F)
        if (!nativeCheckSymbol(outSo.absolutePath, "run_user_main")) {
            return@coroutineScope ExecResult(
                command = "dlsym run_user_main",
                exitCode = -1, stdout = compileResult.stdout, stderr = compileResult.stderr,
                durationMs = System.currentTimeMillis() - started,
                error = "compiled .so missing run_user_main symbol"
            )
        }

        val stdoutPipe = android.os.ParcelFileDescriptor.createPipe()
        val stderrPipe = android.os.ParcelFileDescriptor.createPipe()
        val stdoutRead = stdoutPipe[0]
        val stdoutWriteFd = stdoutPipe[1].detachFd()
        val stderrRead = stderrPipe[0]
        val stderrWriteFd = stderrPipe[1].detachFd()

        val stdoutDeferred = async(Dispatchers.IO) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(stdoutRead).use { it.readBytes().decodeToString() }
        }
        val stderrDeferred = async(Dispatchers.IO) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(stderrRead).use { it.readBytes().decodeToString() }
        }

        val rc = withContext(Dispatchers.IO) {
            try {
                nativeRunUserProgram(outSo.absolutePath, stdoutWriteFd, stderrWriteFd)
            } catch (t: Throwable) {
                -9999
            } finally {
                try { android.os.ParcelFileDescriptor.adoptFd(stdoutWriteFd).close() } catch (_: Throwable) {}
                try { android.os.ParcelFileDescriptor.adoptFd(stderrWriteFd).close() } catch (_: Throwable) {}
            }
        }

        val userOut = stdoutDeferred.await()
        val userErr = stderrDeferred.await()

        ExecResult(
            command = "ON-DEVICE COMPILE + RUN",
            exitCode = rc,
            stdout = buildString {
                append("=== clang -v output ===\n")
                append(compileResult.stderr.take(2000))  // -v emits to stderr
                append("\n=== user program stdout ===\n")
                append(userOut)
            },
            stderr = buildString {
                if (userErr.isNotBlank()) {
                    append("=== user program stderr ===\n")
                    append(userErr)
                }
            },
            durationMs = System.currentTimeMillis() - started,
        )
    }

    // ---------- env helper ----------

    private fun buildToolchainEnv(): Map<String, String> {
        val libPath = buildString {
            append(nativeLibDir.absolutePath)
            append(':')
            append(extractor.libDir.absolutePath)
        }
        return mapOf(
            "PATH" to extractor.binDir.absolutePath,
            "LD_LIBRARY_PATH" to libPath,
            "HOME" to workDir.absolutePath,
            "TMPDIR" to workDir.absolutePath,
        )
    }

    // ---------- core exec helper ----------

    private fun exec(
        command: List<String>,
        extraEnv: Map<String, String> = emptyMap(),
    ): ExecResult {
        val started = System.currentTimeMillis()
        return try {
            val pb = ProcessBuilder(command)
                .redirectErrorStream(false)
                .directory(workDir)
            pb.environment().apply {
                // Give child processes a sane sandbox env.
                put("HOME", workDir.absolutePath)
                put("TMPDIR", workDir.absolutePath)
                put("LD_LIBRARY_PATH", nativeLibDir.absolutePath)
                putAll(extraEnv)
            }
            val proc = pb.start()
            val stdout = proc.inputStream.bufferedReader().readText()
            val stderr = proc.errorStream.bufferedReader().readText()
            val exit = proc.waitFor()
            ExecResult(
                command = command.joinToString(" "),
                exitCode = exit,
                stdout = stdout,
                stderr = stderr,
                durationMs = System.currentTimeMillis() - started,
            )
        } catch (e: Exception) {
            ExecResult(
                command = command.joinToString(" "),
                exitCode = -1,
                stdout = "",
                stderr = "",
                durationMs = System.currentTimeMillis() - started,
                error = "${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }
}
