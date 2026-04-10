package dev.cppide.core.run

import kotlinx.coroutines.flow.Flow

/**
 * Executes compiled user programs in-process via dlopen + JNI. On Android 15
 * (and since SELinux `untrusted_app_35` denies `execve` of files written to
 * `filesDir`) this is the only way to run freshly-compiled code the user's
 * IDE just produced. The runtime shim inside the compiled .so takes pipe fds
 * and dup2()'s them over fd 1/2 so the user's printf/cout is captured.
 */
interface RunService {
    /**
     * Run the configured library once. The returned flow emits [RunEvent.Started]
     * first, then any number of stdout/stderr chunks, then exactly one of
     * [RunEvent.Exited] / [RunEvent.Failed]. Cold — collection starts the run.
     */
    fun run(config: RunConfig): Flow<RunEvent>
}
