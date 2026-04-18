package dev.cppide.core.run

import android.os.ParcelFileDescriptor
import dev.cppide.core.common.DispatcherProvider
import dev.cppide.core.common.pumpUtf8Into
import dev.cppide.core.jni.NativeBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Default in-process runner. Creates three anonymous pipes, hands the
 * stdout/stderr write ends and the stdin read end to the JNI bridge which
 * dup2's them onto the inferior's std{in,out,err}. Streams the stdout /
 * stderr read ends as [RunEvent.Stdout] / [RunEvent.Stderr] chunks, and
 * forwards any emissions from [RunConfig.stdin] to the stdin pipe so the
 * user's `cin` sees them.
 */
class DefaultRunService(
    private val dispatchers: DispatcherProvider,
) : RunService {

    override fun run(config: RunConfig): Flow<RunEvent> = channelFlow {
        val started = System.currentTimeMillis()
        send(RunEvent.Started)

        if (!NativeBridge.isLoaded) {
            send(RunEvent.Failed("libjnibridge.so failed to load", 0))
            return@channelFlow
        }
        if (!config.library.exists()) {
            send(RunEvent.Failed("library not found: ${config.library.absolutePath}", 0))
            return@channelFlow
        }
        if (!NativeBridge.hasSymbol(config.library.absolutePath, "run_user_main")) {
            send(RunEvent.Failed("library missing run_user_main symbol", System.currentTimeMillis() - started))
            return@channelFlow
        }

        val stdoutPipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (t: Throwable) {
            send(RunEvent.Failed("pipe(stdout) failed: ${t.message}", System.currentTimeMillis() - started))
            return@channelFlow
        }
        val stderrPipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (t: Throwable) {
            stdoutPipe.forEach { it.close() }
            send(RunEvent.Failed("pipe(stderr) failed: ${t.message}", System.currentTimeMillis() - started))
            return@channelFlow
        }
        // fd=-1 means "leave inferior's stdin closed" — cin returns EOF on first read.
        val stdinPipe = if (config.stdin != null) {
            try {
                ParcelFileDescriptor.createPipe()
            } catch (t: Throwable) {
                stdoutPipe.forEach { it.close() }
                stderrPipe.forEach { it.close() }
                send(RunEvent.Failed("pipe(stdin) failed: ${t.message}", System.currentTimeMillis() - started))
                return@channelFlow
            }
        } else null

        val stdoutRead = stdoutPipe[0]
        val stdoutWriteFd = stdoutPipe[1].detachFd()
        val stderrRead = stderrPipe[0]
        val stderrWriteFd = stderrPipe[1].detachFd()
        val stdinReadFd = stdinPipe?.get(0)?.detachFd() ?: -1
        val stdinWrite = stdinPipe?.get(1)

        val stdoutJob = launch(dispatchers.io) {
            ParcelFileDescriptor.AutoCloseInputStream(stdoutRead).use { input ->
                val buf = ByteArray(4096)
                while (true) {
                    val n = try { input.read(buf) } catch (_: Throwable) { -1 }
                    if (n <= 0) break
                    send(RunEvent.Stdout(String(buf, 0, n)))
                }
            }
        }
        val stderrJob = launch(dispatchers.io) {
            ParcelFileDescriptor.AutoCloseInputStream(stderrRead).use { input ->
                val buf = ByteArray(4096)
                while (true) {
                    val n = try { input.read(buf) } catch (_: Throwable) { -1 }
                    if (n <= 0) break
                    send(RunEvent.Stderr(String(buf, 0, n)))
                }
            }
        }

        val stdinJob = if (stdinWrite != null && config.stdin != null) {
            launch(dispatchers.io) {
                try {
                    config.stdin.pumpUtf8Into(
                        ParcelFileDescriptor.AutoCloseOutputStream(stdinWrite),
                    )
                } catch (_: CancellationException) {
                    // normal shutdown
                }
            }
        } else null

        // Run the program on the IO dispatcher — blocks until user main returns.
        val rc = withContext(dispatchers.io) {
            try {
                NativeBridge.runUserProgram(
                    config.library.absolutePath,
                    stdinReadFd,
                    stdoutWriteFd,
                    stderrWriteFd,
                )
            } catch (t: Throwable) {
                -9999
            } finally {
                // Closing the write fds signals EOF to the reader jobs.
                try { ParcelFileDescriptor.adoptFd(stdoutWriteFd).close() } catch (_: Throwable) {}
                try { ParcelFileDescriptor.adoptFd(stderrWriteFd).close() } catch (_: Throwable) {}
            }
        }

        // Stop the stdin pump before joining readers so its AutoCloseOutputStream
        // releases the write-end; otherwise collect{} can keep it open.
        stdinJob?.cancel()
        stdinJob?.join()
        stdoutJob.join()
        stderrJob.join()

        val durationMs = System.currentTimeMillis() - started
        send(
            if (rc < -1000) RunEvent.Failed("JNI runUserProgram returned $rc", durationMs)
            else RunEvent.Exited(rc, durationMs)
        )
    }
}
