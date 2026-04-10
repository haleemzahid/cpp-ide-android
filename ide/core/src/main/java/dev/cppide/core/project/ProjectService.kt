package dev.cppide.core.project

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Manages the active project and its file tree. One project is open at a
 * time; switching closes the previous one.
 *
 * The current implementation ([DefaultProjectService]) works against a real
 * filesystem path under app-private storage. A SAF-backed adapter for
 * user-chosen external folders can be added later without changing this
 * interface.
 */
interface ProjectService {

    /** Currently open project or null. Observed by the file tree UI. */
    val currentProject: StateFlow<Project?>

    /**
     * Current file tree of the open project, or null. Rebuilt by [refresh]
     * and by any mutating operation below.
     */
    val fileTree: StateFlow<ProjectNode.Directory?>

    // ---- lifecycle ----

    /** Open [root] as the active project. Creates the directory if missing. */
    suspend fun open(root: File, displayName: String = root.name): Result<Project>

    /** Drop the current project from memory. Does not delete files. */
    suspend fun close()

    /** Re-scan the project root and publish an updated [fileTree]. */
    suspend fun refresh(): Result<ProjectNode.Directory>

    // ---- file I/O ----

    suspend fun read(relativePath: String): Result<String>

    suspend fun write(relativePath: String, content: String): Result<Unit>

    // ---- mutations ----

    suspend fun createFile(relativePath: String, content: String = ""): Result<File>

    suspend fun createDirectory(relativePath: String): Result<File>

    suspend fun rename(from: String, to: String): Result<File>

    suspend fun delete(relativePath: String): Result<Unit>

    // ---- resolution ----

    /** Convert a relative path into an absolute [File] inside the project root. */
    fun resolve(relativePath: String): File
}
