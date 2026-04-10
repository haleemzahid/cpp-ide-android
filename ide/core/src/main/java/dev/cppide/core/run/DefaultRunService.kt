package dev.cppide.core.run

import android.os.ParcelFileDescriptor
import dev.cppide.core.common.DispatcherProvider
import dev.cppide.core.jni.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Default in-process runner. Creates two anonymous pipes, hands the write
 * ends to the JNI bridge which dup2's them onto stdout/stderr inside the
 * dlopen'd user library, and streams the read ends as [RunEvent.Stdout] /
 * [RunEvent.Stderr] chunks.
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

        val stdoutRead = stdoutPipe[0]
        val stdoutWriteFd = stdoutPipe[1].detachFd()
        val stderrRead = stderrPipe[0]
        val stderrWriteFd = stderrPipe[1].detachFd()

        // Reader coroutines pipe output as it arrives.
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

        // Run the program on the IO dispatcher — blocks until user main returns.
        val rc = withContext(dispatchers.io) {
            try {
                NativeBridge.runUserProgram(config.library.absolutePath, stdoutWriteFd, stderrWriteFd)
            } catch (t: Throwable) {
                -9999
            } finally {
                // Closing the write fds signals EOF to the reader jobs.
                try { ParcelFileDescriptor.adoptFd(stdoutWriteFd).close() } catch (_: Throwable) {}
                try { ParcelFileDescriptor.adoptFd(stderrWriteFd).close() } catch (_: Throwable) {}
            }
        }

        stdoutJob.join()
        stderrJob.join()

        val durationMs = System.currentTimeMillis() - started
        send(
            if (rc < -1000) RunEvent.Failed("JNI runUserProgram returned $rc", durationMs)
            else RunEvent.Exited(rc, durationMs)
        )
    }
}
