package dev.cppide.core.toolchain

import kotlinx.coroutines.flow.StateFlow

/**
 * Manages the bundled C++ toolchain (clang, clangd, lld, lldb, sysroot).
 * Exactly one implementation exists today — [TermuxToolchain] — but the
 * interface leaves room for alternative backends (LLVM built from source,
 * debug-only stub, etc).
 */
interface Toolchain {

    /** Observable status. UI can render progress / errors by collecting this. */
    val state: StateFlow<ToolchainState>

    /** Currently resolved paths, or null if not yet installed. */
    val paths: ToolchainPaths?

    /**
     * Idempotent install. Extracts bundled assets if needed and always
     * re-validates the bin/ symlinks (nativeLibraryDir changes on every
     * APK reinstall, so old links become dangling).
     */
    suspend fun install(progress: (String) -> Unit = {}): Result<ToolchainPaths>

    /** True iff [paths] is non-null and the marker indicates a good install. */
    fun isReady(): Boolean

    /** Wipe the on-disk extraction. Next [install] call starts fresh. */
    suspend fun uninstall()
}

sealed interface ToolchainState {
    data object NotInstalled : ToolchainState
    data class Installing(val message: String) : ToolchainState
    data class Ready(val paths: ToolchainPaths) : ToolchainState
    data class Error(val message: String, val cause: Throwable? = null) : ToolchainState
}
