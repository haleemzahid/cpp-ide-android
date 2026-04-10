package dev.cppide.core.project

import java.io.File

/**
 * A C++ project living under a single root directory.
 *
 * "Project" is a thin concept: a root folder + a display name. Any
 * CMakeLists.txt / compile_commands.json / etc. is discovered by BuildService
 * and LspService rather than persisted here.
 */
data class Project(
    val name: String,
    val root: File,
    val createdAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = System.currentTimeMillis(),
)
