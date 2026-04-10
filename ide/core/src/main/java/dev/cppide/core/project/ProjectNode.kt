package dev.cppide.core.project

/**
 * A node in the project file tree. Immutable — the project service rebuilds
 * the tree on every refresh and publishes it via a StateFlow.
 *
 * [relativePath] is always relative to the project root, uses forward slashes,
 * and never starts with `/`. The root itself has `relativePath = ""`.
 */
sealed class ProjectNode {
    abstract val name: String
    abstract val relativePath: String

    data class File(
        override val name: String,
        override val relativePath: String,
        val sizeBytes: Long,
        val modifiedAt: Long,
    ) : ProjectNode()

    data class Directory(
        override val name: String,
        override val relativePath: String,
        val children: List<ProjectNode>,
    ) : ProjectNode() {
        /** Walk this directory subtree depth-first, including the directory itself. */
        fun walk(): Sequence<ProjectNode> = sequence {
            yield(this@Directory)
            for (c in children) {
                when (c) {
                    is File -> yield(c)
                    is Directory -> yieldAll(c.walk())
                }
            }
        }
    }

    companion object {
        /** True for files an IDE should skip when scanning (VCS dirs, build artifacts, etc). */
        fun isIgnored(name: String): Boolean = name in IGNORED || name.startsWith(".")
        private val IGNORED = setOf(
            "build", "cmake-build-debug", "cmake-build-release", ".idea", ".vscode",
            "node_modules", "__pycache__", ".git", ".svn", ".hg",
        )
    }
}
