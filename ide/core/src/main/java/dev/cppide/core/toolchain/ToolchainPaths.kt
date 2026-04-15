package dev.cppide.core.toolchain

import java.io.File

/**
 * Concrete paths to every toolchain component, resolved after [Toolchain.install].
 * All paths are absolute; the UI and services below don't need to know Termux's
 * naming conventions or where the bundled .so files landed on this install.
 */
data class ToolchainPaths(
    /** Symlink: filesDir/termux/bin/clang → nativeLibraryDir/libclang.so */
    val clang: File,

    /** Symlink: filesDir/termux/bin/clang++ → nativeLibraryDir/libclang.so */
    val clangxx: File,

    /** Symlink: filesDir/termux/bin/ld → nativeLibraryDir/libld.so (lld multicall) */
    val ld: File,

    /** Symlink: filesDir/termux/bin/clangd → nativeLibraryDir/libclangd.so (LSP server) */
    val clangd: File,

    /** Symlink: filesDir/termux/bin/lldb-server → nativeLibraryDir/libLLDBServer.so */
    val lldbServer: File,

    /** Symlink: filesDir/termux/bin/lldb-dap → nativeLibraryDir/libLLDBDAP.so */
    val lldbDap: File,

    /** filesDir/termux/sysroot — clang passes this via --sysroot= */
    val sysroot: File,

    /** filesDir/termux/lib/clang/<v> — clang passes via -resource-dir= */
    val resourceDir: File,

    /** applicationInfo.nativeLibraryDir — holds clang/lld binaries + libc++_shared */
    val nativeLibDir: File,

    /** filesDir/termux/lib — holds versioned-soname libs extracted from termux.zip */
    val termuxLibDir: File,

    /** filesDir/termux/bin — the directory of PATH symlinks */
    val binDir: File,

    /** filesDir/termux — root of the extracted Termux tree */
    val termuxRoot: File,
) {
    /**
     * Environment map for spawning any toolchain subprocess (clang, clangd, lldb).
     *
     * `PYTHONHOME` points at termuxRoot so that `liblldb.so`'s Py_Initialize()
     * finds the stdlib at `termuxRoot/lib/python3.13/`. Without it, Python init
     * aborts (because the compiled-in prefix `/data/data/com.termux/...` doesn't
     * exist in our app sandbox) and lldb-dap crashes on startup.
     *
     * `PYTHONDONTWRITEBYTECODE` keeps Python from writing .pyc files into
     * termux.zip's extracted tree — they'd clutter filesDir with no benefit.
     *
     * These vars are harmless for non-Python toolchain tools (clang, lld, etc.),
     * so we set them unconditionally rather than splitting the env map.
     */
    fun processEnv(workingDir: File): Map<String, String> = mapOf(
        "PATH" to binDir.absolutePath,
        "LD_LIBRARY_PATH" to "${nativeLibDir.absolutePath}:${termuxLibDir.absolutePath}",
        "HOME" to workingDir.absolutePath,
        "TMPDIR" to workingDir.absolutePath,
        "PYTHONHOME" to termuxRoot.absolutePath,
        "PYTHONPATH" to "${termuxLibDir.absolutePath}/python3.13/site-packages",
        "PYTHONDONTWRITEBYTECODE" to "1",
    )
}
