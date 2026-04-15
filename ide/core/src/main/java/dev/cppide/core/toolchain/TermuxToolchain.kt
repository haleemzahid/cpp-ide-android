package dev.cppide.core.toolchain

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import dev.cppide.core.common.DispatcherProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Ships the Termux aarch64 build of LLVM (clang, clang++, lld, libLLVM,
 * libclang-cpp, libc++_shared) inside the APK.
 *
 * Layout contract:
 *   APK jniLibs/arm64-v8a/   — clang, lld binaries renamed libX.so;
 *                              libLLVM.so, libclang-cpp.so, libc++_shared.so;
 *                              libjnibridge.so (our glue)
 *   APK assets/termux.zip    — sysroot + clang resource dir + versioned-soname libs
 *
 * On first run: unzip assets/termux.zip into filesDir/termux/, then create
 * bin/ symlinks pointing at the real ELFs in nativeLibraryDir.
 *
 * On every subsequent run: re-verify and repair those bin/ symlinks — Android
 * regenerates nativeLibraryDir with a random path suffix on every APK install,
 * so links written during a previous install are dangling after reinstall.
 */
class TermuxToolchain(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) : Toolchain {

    private val _state = MutableStateFlow<ToolchainState>(ToolchainState.NotInstalled)
    override val state = _state.asStateFlow()

    override val paths: ToolchainPaths?
        get() = (_state.value as? ToolchainState.Ready)?.paths

    private val termuxRoot: File get() = File(context.filesDir, "termux")
    private val binDir: File get() = File(termuxRoot, "bin")
    private val libDir: File get() = File(termuxRoot, "lib")
    private val sysroot: File get() = File(termuxRoot, "sysroot")
    private val resourceDir: File get() = File(termuxRoot, "lib/clang/$CLANG_RESOURCE_VERSION")
    private val nativeLibDir: File get() = File(context.applicationInfo.nativeLibraryDir)
    private val marker: File get() = File(termuxRoot, ".installed_v$MARKER_VERSION")

    init {
        // If an install from a previous app launch is still good, publish it eagerly.
        if (marker.exists()) {
            _state.value = ToolchainState.Ready(computePaths())
        }
    }

    override fun isReady(): Boolean = _state.value is ToolchainState.Ready

    override suspend fun install(progress: (String) -> Unit): Result<ToolchainPaths> =
        withContext(dispatchers.io) {
            runCatching {
                _state.value = ToolchainState.Installing("starting")

                if (!marker.exists()) {
                    if (termuxRoot.exists()) {
                        progress("wiping stale install")
                        _state.value = ToolchainState.Installing("wiping stale install")
                        termuxRoot.deleteRecursively()
                    }
                    termuxRoot.mkdirs()
                    extractTermuxZip { msg ->
                        progress(msg)
                        _state.value = ToolchainState.Installing(msg)
                    }
                    marker.writeText("installed at ${System.currentTimeMillis()}")
                }

                progress("verifying symlinks")
                ensureSymlinks()

                val p = computePaths()
                _state.value = ToolchainState.Ready(p)
                p
            }.onFailure { t ->
                Log.e(TAG, "install failed", t)
                _state.value = ToolchainState.Error(
                    message = "${t.javaClass.simpleName}: ${t.message}",
                    cause = t,
                )
            }
        }

    override suspend fun uninstall() = withContext(dispatchers.io) {
        termuxRoot.deleteRecursively()
        _state.value = ToolchainState.NotInstalled
    }

    // ---- extraction ----

    private fun extractTermuxZip(progress: (String) -> Unit) {
        progress("extracting termux.zip")
        var fileCount = 0
        var bytesWritten = 0L
        context.assets.open(TERMUX_ZIP).use { input ->
            ZipInputStream(input).use { zis ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val out = File(termuxRoot, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos ->
                            while (true) {
                                val n = zis.read(buf)
                                if (n <= 0) break
                                fos.write(buf, 0, n)
                                bytesWritten += n
                            }
                        }
                        fileCount++
                        if (fileCount % 500 == 0) {
                            progress("extracted $fileCount files (${bytesWritten / (1024 * 1024)} MB)")
                        }
                    }
                    zis.closeEntry()
                }
            }
        }
        progress("extracted $fileCount files (${bytesWritten / (1024 * 1024)} MB) total")
    }

    // ---- symlink repair ----

    /**
     * Because `nativeLibraryDir` has a random suffix that changes on every
     * install, symlinks pointing at the old path break after reinstall.
     * Validate on every call; recreate anything that's stale.
     */
    private fun ensureSymlinks(): Int {
        binDir.mkdirs()
        val clangTarget = File(nativeLibDir, "libclang.so").absolutePath
        val ldTarget = File(nativeLibDir, "libld.so").absolutePath
        val clangdTarget = File(nativeLibDir, "libclangd.so").absolutePath
        val lldbServerTarget = File(nativeLibDir, "libLLDBServer.so").absolutePath
        val lldbDapTarget = File(nativeLibDir, "libLLDBDAP.so").absolutePath

        var created = 0
        fun linkIfStale(target: String, name: String) {
            val link = File(binDir, name)
            val existing = try { Os.readlink(link.absolutePath) } catch (_: Throwable) { null }
            if (existing == target) return
            if (link.exists() || link.isSymlink()) link.delete()
            try {
                Os.symlink(target, link.absolutePath)
                created++
            } catch (e: ErrnoException) {
                Log.e(TAG, "symlink $name -> $target failed", e)
            }
        }
        CLANG_ALIASES.forEach { linkIfStale(clangTarget, it) }
        LD_ALIASES.forEach { linkIfStale(ldTarget, it) }
        linkIfStale(clangdTarget, "clangd")
        linkIfStale(lldbServerTarget, "lldb-server")
        linkIfStale(lldbDapTarget, "lldb-dap")
        return created
    }

    private fun computePaths() = ToolchainPaths(
        clang = File(binDir, "clang"),
        clangxx = File(binDir, "clang++"),
        ld = File(binDir, "ld"),
        clangd = File(binDir, "clangd"),
        lldbServer = File(binDir, "lldb-server"),
        lldbDap = File(binDir, "lldb-dap"),
        sysroot = sysroot,
        resourceDir = resourceDir,
        nativeLibDir = nativeLibDir,
        termuxLibDir = libDir,
        binDir = binDir,
        termuxRoot = termuxRoot,
    )

    private fun File.isSymlink(): Boolean =
        try { Os.lstat(absolutePath).st_mode and 0xF000 == 0xA000 } catch (_: Throwable) { false }

    companion object {
        private const val TAG = "cppide-core"
        private const val TERMUX_ZIP = "termux.zip"

        // Bumped when the termux.zip schema changes so old extractions wipe automatically.
        // v3: added liblzma.so.5 for lldb-server.
        // v4: libLLVM.so deduped out of termux.zip (now lives only in jniLibs/);
        //     sanitizer archives trimmed to builtins + asan + ubsan.
        // v5: added liblldb.so, libpython3.13.so + stdlib, lldb Python bindings,
        //     libicu*, openssl, ncurses, libedit, libxml2 for lldb-dap.
        private const val MARKER_VERSION = 5
        private const val CLANG_RESOURCE_VERSION = "21"

        private val CLANG_ALIASES = listOf(
            "clang", "clang++", "clang-21", "clang++-21",
            "cc", "c++", "gcc", "g++",
            "aarch64-linux-android-clang", "aarch64-linux-android-clang++",
        )
        private val LD_ALIASES = listOf(
            "lld", "ld", "ld.lld", "ld64.lld", "lld-link", "wasm-ld",
            "aarch64-linux-android-ld",
        )
    }
}
