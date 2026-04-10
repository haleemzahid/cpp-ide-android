package dev.cppide.core.lsp

import dev.cppide.core.toolchain.ToolchainPaths
import java.io.File

/**
 * Builds a `compile_commands.json` for clangd. clangd uses this file to
 * know exactly which flags to pass when parsing each source file —
 * sysroot, target triple, include dirs, defines.
 *
 * The flags MUST match what [dev.cppide.core.build.ClangBuildService]
 * uses, otherwise the editor and the build will diverge (clangd would say
 * a header is missing while the build succeeds, or vice versa).
 *
 * Format reference: https://clang.llvm.org/docs/JSONCompilationDatabase.html
 *
 * Each entry is one source file with the full argv it would be invoked
 * with. We use the "arguments" form (array of strings) for safety.
 */
object CompileCommandsGenerator {

    /**
     * Walk [projectRoot] for .cpp/.c/.cc/.cxx files, generate one entry per
     * file, and write the result to `<projectRoot>/.cppide/compile_commands.json`.
     * clangd looks for this file walking up from each source.
     *
     * Returns the path to the generated file.
     */
    fun generate(
        projectRoot: File,
        toolchain: ToolchainPaths,
        targetApi: Int = 26,
    ): File {
        val sources = collectSources(projectRoot)
        val entries = sources.map { src -> entryForFile(src, projectRoot, toolchain, targetApi) }

        val outDir = File(projectRoot, ".cppide").apply { mkdirs() }
        val out = File(outDir, "compile_commands.json")
        out.writeText(serialize(entries))
        return out
    }

    private fun collectSources(root: File): List<File> {
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in CPP_EXTENSIONS }
            .filter { !it.path.contains("${File.separator}.cppide${File.separator}") }
            .filter { !it.path.contains("${File.separator}build${File.separator}") }
            .toList()
    }

    private fun entryForFile(
        source: File,
        projectRoot: File,
        toolchain: ToolchainPaths,
        targetApi: Int,
    ): Entry {
        val isCpp = source.extension.lowercase() != "c"
        val driver = if (isCpp) toolchain.clangxx.absolutePath else toolchain.clang.absolutePath

        val args = mutableListOf<String>()
        args += driver
        args += "-target"
        args += "aarch64-linux-android$targetApi"
        args += "--sysroot=${toolchain.sysroot.absolutePath}"
        args += "-resource-dir=${toolchain.resourceDir.absolutePath}"
        args += if (isCpp) "-std=c++20" else "-std=c17"
        args += "-fPIC"
        args += "-Wall"
        args += "-x"
        args += if (isCpp) "c++" else "c"
        args += source.absolutePath

        return Entry(
            directory = projectRoot.absolutePath,
            file = source.absolutePath,
            arguments = args,
        )
    }

    /** Tiny hand-rolled JSON serializer — no external dep needed for ~10 fields. */
    private fun serialize(entries: List<Entry>): String {
        if (entries.isEmpty()) return "[]\n"
        val sb = StringBuilder()
        sb.append("[\n")
        entries.forEachIndexed { i, e ->
            sb.append("  {\n")
            sb.append("    \"directory\": ").append(jsonString(e.directory)).append(",\n")
            sb.append("    \"file\": ").append(jsonString(e.file)).append(",\n")
            sb.append("    \"arguments\": [\n")
            e.arguments.forEachIndexed { j, arg ->
                sb.append("      ").append(jsonString(arg))
                if (j != e.arguments.lastIndex) sb.append(',')
                sb.append('\n')
            }
            sb.append("    ]\n")
            sb.append("  }")
            if (i != entries.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("]\n")
        return sb.toString()
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private data class Entry(
        val directory: String,
        val file: String,
        val arguments: List<String>,
    )

    private val CPP_EXTENSIONS = setOf("cpp", "cc", "cxx", "c++", "c", "h", "hpp", "hh", "hxx")
}
