package dev.cppide.core.project

import dev.cppide.core.common.DispatcherProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Real-filesystem [ProjectService] implementation. Reads/writes happen under
 * a single root directory; paths are sanitised so a `..` segment can't escape
 * the project.
 *
 * All blocking I/O runs on [DispatcherProvider.io].
 */
class DefaultProjectService(
    private val dispatchers: DispatcherProvider,
) : ProjectService {

    private val _currentProject = MutableStateFlow<Project?>(null)
    override val currentProject = _currentProject.asStateFlow()

    private val _fileTree = MutableStateFlow<ProjectNode.Directory?>(null)
    override val fileTree = _fileTree.asStateFlow()

    override suspend fun open(root: File, displayName: String): Result<Project> =
        withContext(dispatchers.io) {
            runCatching {
                if (!root.exists()) {
                    if (!root.mkdirs()) error("could not create project root: $root")
                }
                if (!root.isDirectory) error("not a directory: $root")
                if (!root.canRead()) error("cannot read project root: $root")

                val project = Project(
                    name = displayName,
                    root = root.canonicalFile,
                    lastOpenedAt = System.currentTimeMillis(),
                )
                _currentProject.value = project
                _fileTree.value = scanTree(project.root)
                project
            }
        }

    override suspend fun close() = withContext(dispatchers.io) {
        _currentProject.value = null
        _fileTree.value = null
    }

    override suspend fun refresh(): Result<ProjectNode.Directory> =
        withContext(dispatchers.io) {
            runCatching {
                val project = _currentProject.value ?: error("no project open")
                val tree = scanTree(project.root)
                _fileTree.value = tree
                tree
            }
        }

    // ---- file I/O ----

    override suspend fun read(relativePath: String): Result<String> =
        withContext(dispatchers.io) {
            runCatching {
                val file = safeResolve(relativePath)
                if (!file.isFile) error("not a file: $relativePath")
                file.readText()
            }
        }

    override suspend fun write(relativePath: String, content: String): Result<Unit> =
        withContext(dispatchers.io) {
            runCatching {
                val file = safeResolve(relativePath)
                file.parentFile?.mkdirs()
                // Atomic-ish write: write to tmp then rename.
                val tmp = File(file.parentFile, ".${file.name}.tmp")
                tmp.writeText(content)
                if (!tmp.renameTo(file)) {
                    // Fallback: direct write if rename failed (e.g. cross-FS).
                    tmp.delete()
                    file.writeText(content)
                }
                refreshTreeSilently()
            }
        }

    // ---- mutations ----

    override suspend fun createFile(relativePath: String, content: String): Result<File> =
        withContext(dispatchers.io) {
            runCatching {
                val file = safeResolve(relativePath)
                if (file.exists()) error("file already exists: $relativePath")
                file.parentFile?.mkdirs()
                file.writeText(content)
                refreshTreeSilently()
                file
            }
        }

    override suspend fun createDirectory(relativePath: String): Result<File> =
        withContext(dispatchers.io) {
            runCatching {
                val dir = safeResolve(relativePath)
                if (dir.exists()) error("path already exists: $relativePath")
                if (!dir.mkdirs()) error("failed to create directory: $relativePath")
                refreshTreeSilently()
                dir
            }
        }

    override suspend fun rename(from: String, to: String): Result<File> =
        withContext(dispatchers.io) {
            runCatching {
                val src = safeResolve(from)
                val dst = safeResolve(to)
                if (!src.exists()) error("source does not exist: $from")
                if (dst.exists()) error("destination already exists: $to")
                dst.parentFile?.mkdirs()
                if (!src.renameTo(dst)) error("rename $from → $to failed")
                refreshTreeSilently()
                dst
            }
        }

    override suspend fun delete(relativePath: String): Result<Unit> =
        withContext(dispatchers.io) {
            runCatching {
                val file = safeResolve(relativePath)
                if (!file.exists()) error("does not exist: $relativePath")
                val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (!ok) error("failed to delete: $relativePath")
                refreshTreeSilently()
            }
        }

    // ---- path resolution ----

    override fun resolve(relativePath: String): File = safeResolve(relativePath)

    /**
     * Resolve a relative path into an absolute [File] under the project root,
     * rejecting anything that would escape via `..` segments or absolute paths.
     */
    private fun safeResolve(relativePath: String): File {
        val project = _currentProject.value ?: throw IOException("no project open")
        val clean = relativePath.trim().trimStart('/')
        if (clean.isEmpty()) return project.root
        val file = File(project.root, clean).canonicalFile
        val root = project.root.canonicalFile
        if (!file.path.startsWith(root.path)) {
            throw IOException("path escapes project root: $relativePath")
        }
        return file
    }

    // ---- tree scan ----

    private fun scanTree(root: File): ProjectNode.Directory {
        fun build(dir: File, relative: String): ProjectNode.Directory {
            val children = (dir.listFiles() ?: emptyArray())
                .asSequence()
                .filter { !ProjectNode.isIgnored(it.name) }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .map { child ->
                    val childRel = if (relative.isEmpty()) child.name else "$relative/${child.name}"
                    if (child.isDirectory) {
                        build(child, childRel)
                    } else {
                        ProjectNode.File(
                            name = child.name,
                            relativePath = childRel,
                            sizeBytes = child.length(),
                            modifiedAt = child.lastModified(),
                        )
                    }
                }
                .toList()
            return ProjectNode.Directory(
                name = if (relative.isEmpty()) root.name else dir.name,
                relativePath = relative,
                children = children,
            )
        }
        return build(root, "")
    }

    private fun refreshTreeSilently() {
        val project = _currentProject.value ?: return
        _fileTree.value = scanTree(project.root)
    }
}
