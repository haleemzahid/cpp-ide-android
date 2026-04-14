package dev.cppide.core.session

data class RecentFile(
    val filePath: String,
    val projectRoot: String,
    val projectName: String,
    val relativePath: String,
    val displayName: String,
    val lastOpenedAt: Long,
)
