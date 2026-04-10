package dev.cppide.core

import android.content.Context
import dev.cppide.core.build.BuildService
import dev.cppide.core.build.ClangBuildService
import dev.cppide.core.common.DefaultDispatchers
import dev.cppide.core.common.DispatcherProvider
import dev.cppide.core.lsp.ClangdLspService
import dev.cppide.core.lsp.LspService
import dev.cppide.core.project.DefaultProjectService
import dev.cppide.core.project.ProjectService
import dev.cppide.core.run.DefaultRunService
import dev.cppide.core.run.RunService
import dev.cppide.core.session.RoomSessionRepository
import dev.cppide.core.session.SessionRepository
import dev.cppide.core.toolchain.TermuxToolchain
import dev.cppide.core.toolchain.Toolchain
import java.io.File

/**
 * Single entry point the host app (or tests) uses to obtain core services.
 * Think of it as a hand-rolled DI container: it wires the default
 * implementations together and exposes them as interfaces.
 *
 * ```
 * val core = Core.create(applicationContext)
 * val result = core.buildService.build(config)
 * ```
 *
 * All services share one [DispatcherProvider] and one [Toolchain] instance
 * per [Core] so they see the same state.
 */
class Core private constructor(
    val context: Context,
    val dispatchers: DispatcherProvider,
    val toolchain: Toolchain,
    val buildService: BuildService,
    val runService: RunService,
    val projectService: ProjectService,
    val sessionRepository: SessionRepository,
    val lspService: LspService,
) {

    /**
     * Copies the bundled runtime shim source (runtime_shim.cpp) from assets
     * into app-private storage so [BuildService] can pass it to clang++
     * when `BuildConfig.wrapMain = true`. Idempotent — the second call is
     * a no-op if the file is already present and the assets haven't changed.
     */
    fun runtimeShimSource(): File {
        val dst = File(context.filesDir, "runtime/runtime_shim.cpp")
        if (!dst.exists() || dst.length() == 0L) {
            dst.parentFile?.mkdirs()
            context.assets.open(RUNTIME_SHIM_ASSET).use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dst
    }

    /** Convenient scratch directory under filesDir for build outputs, tmp files, etc. */
    val workDir: File
        get() = File(context.filesDir, "work").apply { mkdirs() }

    companion object {
        private const val RUNTIME_SHIM_ASSET = "runtime_shim.cpp"

        /**
         * Construct a default [Core] wired with [TermuxToolchain],
         * [ClangBuildService], [DefaultRunService], [DefaultProjectService],
         * and [RoomSessionRepository]. Safe to call once per process from
         * the Application class.
         */
        fun create(
            context: Context,
            dispatchers: DispatcherProvider = DefaultDispatchers,
        ): Core {
            val app = context.applicationContext
            val toolchain = TermuxToolchain(app, dispatchers)
            return Core(
                context = app,
                dispatchers = dispatchers,
                toolchain = toolchain,
                buildService = ClangBuildService(toolchain, dispatchers),
                runService = DefaultRunService(dispatchers),
                projectService = DefaultProjectService(dispatchers),
                sessionRepository = RoomSessionRepository(app, dispatchers),
                lspService = ClangdLspService(toolchain, dispatchers),
            )
        }
    }
}
