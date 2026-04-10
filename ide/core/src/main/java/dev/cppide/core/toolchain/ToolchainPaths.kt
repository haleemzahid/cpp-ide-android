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
    /** Environment map for spawning any toolchain subprocess (clang, clangd, lldb). */
    fun processEnv(workingDir: File): Map<String, String> = mapOf(
        "PATH" to binDir.absolutePath,
        "LD_LIBRARY_PATH" to "${nativeLibDir.absolutePath}:${termuxLibDir.absolutePath}",
        "HOME" to workingDir.absolutePath,
        "TMPDIR" to workingDir.absolutePath,
    )
}
