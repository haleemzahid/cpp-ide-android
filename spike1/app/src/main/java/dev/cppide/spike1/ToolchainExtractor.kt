package dev.cppide.spike1

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Extracts the bundled Termux toolchain (clang + sysroot + runtime archives)
 * from assets/termux.zip into filesDir/termux on first run, then wires up
 * the symlinks in bin/ that clang and lld expect for their multi-call
 * dispatch (e.g. "ld" -> the real lld binary in nativeLibraryDir).
 *
 * The key insight: exec'ables MUST live in nativeLibraryDir (validated by
 * spike 1 test A — SELinux policy blocks exec from filesDir). So the bin/
 * directory inside filesDir contains only *symlinks* pointing at the real
 * ELF files in nativeLibraryDir. When clang invokes a sub-tool via PATH
 * lookup, it finds a symlink, follows it, and the kernel exec()s the real
 * file out of nativeLibraryDir — which is allowed.
 */
class ToolchainExtractor(private val ctx: Context) {

    companion object {
        private const val TAG = "cppide-spike1"
        // Bump this whenever the termux.zip layout changes, so old extractions
        // on-device get wiped automatically on next install().
        private const val MARKER_VERSION = 2
    }

    val termuxRoot: File get() = File(ctx.filesDir, "termux")
    val binDir: File    get() = File(termuxRoot, "bin")
    val libDir: File    get() = File(termuxRoot, "lib")
    val sysroot: File   get() = File(termuxRoot, "sysroot")
    val clangResDir: File get() = File(termuxRoot, "lib/clang/21")

    private val marker: File get() = File(termuxRoot, ".installed_v$MARKER_VERSION")

    fun isInstalled(): Boolean = marker.exists()

    /** Remove the extraction so the next install() runs fresh (for debugging). */
    fun uninstall() {
        if (termuxRoot.exists()) termuxRoot.deleteRecursively()
    }

    /**
     * Extract assets/termux.zip and set up bin/ symlinks. Idempotent —
     * safe to call on every app start; it's a no-op after the first run.
     */
    suspend fun install(progress: (String) -> Unit = {}): InstallResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val needsExtract = !isInstalled()
        var extractedCount = 0

        if (needsExtract) {
            // If an older/broken extraction exists (marker missing or wrong
            // version), wipe it before re-extracting. This handles schema
            // changes like the sysroot/usr/ fix.
            if (termuxRoot.exists()) {
                progress("wiping stale ${termuxRoot.name}…")
                termuxRoot.deleteRecursively()
            }
            termuxRoot.mkdirs()

            // ---- Extract termux.zip ----
            progress("extracting termux.zip…")
            var bytesWritten = 0L
            ctx.assets.open("termux.zip").use { input ->
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
                            extractedCount++
                            if (extractedCount % 500 == 0) {
                                progress("extracted $extractedCount files (${bytesWritten / (1024 * 1024)} MB)…")
                            }
                        }
                        zis.closeEntry()
                    }
                }
            }
            progress("extracted $extractedCount files total (${bytesWritten / (1024 * 1024)} MB)")
        }

        // ---- Always re-validate bin/ symlinks ----
        //
        // CRITICAL: Android regenerates nativeLibraryDir on every APK install
        // (the path contains a random suffix like /data/app/~~abc123==/.../lib/arm64).
        // filesDir persists but nativeLibraryDir does NOT — so symlinks written
        // during an earlier install() become dangling after the app is reinstalled.
        // We must verify every run that symlinks point at the *current* nativeLibraryDir
        // and rebuild them if not.
        progress("verifying bin/ symlinks…")
        val symlinks = ensureSymlinks()

        if (needsExtract) {
            marker.writeText("installed at ${System.currentTimeMillis()}")
        }

        InstallResult(
            alreadyInstalled = !needsExtract,
            filesExtracted = extractedCount,
            symlinksCreated = symlinks,
            durationMs = System.currentTimeMillis() - started,
        )
    }

    /** Create or repair bin/ symlinks so they point at the *current* nativeLibraryDir.
     *  Returns number of links that had to be (re-)created. */
    private fun ensureSymlinks(): Int {
        binDir.mkdirs()
        val nativeLibDir = ctx.applicationInfo.nativeLibraryDir
        val clangTarget = "$nativeLibDir/libclang.so"
        val ldTarget = "$nativeLibDir/libld.so"

        val clangAliases = listOf(
            "clang", "clang++", "clang-21", "clang++-21",
            "cc", "c++", "gcc", "g++",
            "aarch64-linux-android-clang", "aarch64-linux-android-clang++",
        )
        val ldAliases = listOf(
            "lld", "ld", "ld.lld", "ld64.lld", "lld-link", "wasm-ld",
            "aarch64-linux-android-ld",
        )

        var created = 0
        fun linkIfStale(target: String, name: String) {
            val link = File(binDir, name)
            // If an existing symlink already points at the right place, keep it.
            val existing = try { Os.readlink(link.absolutePath) } catch (_: Throwable) { null }
            if (existing == target) return
            if (link.exists() || link.isSymlink()) link.delete()
            try {
                Os.symlink(target, link.absolutePath)
                created++
            } catch (e: ErrnoException) {
                Log.e(TAG, "symlink failed: $name -> $target", e)
            }
        }
        clangAliases.forEach { linkIfStale(clangTarget, it) }
        ldAliases.forEach { linkIfStale(ldTarget, it) }
        return created
    }

    data class InstallResult(
        val alreadyInstalled: Boolean,
        val filesExtracted: Int,
        val symlinksCreated: Int,
        val durationMs: Long,
    )

    private fun File.isSymlink(): Boolean =
        try { Os.lstat(absolutePath).st_mode and 0xF000 == 0xA000 } catch (_: Throwable) { false }
}
