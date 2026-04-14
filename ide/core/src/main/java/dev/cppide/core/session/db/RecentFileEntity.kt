package dev.cppide.core.session.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.cppide.core.session.RecentFile

@Entity(tableName = "recent_files")
internal data class RecentFileEntity(
    /** Absolute path to the file on disk. */
    @PrimaryKey val filePath: String,
    /** Project root this file belongs to. */
    val projectRoot: String,
    /** Project display name (e.g. "If Else"). */
    val projectName: String,
    /** Relative path within the project (e.g. "comparing-numbers/solution.cpp"). */
    val relativePath: String,
    /** Human-friendly name derived from the file/exercise. */
    val displayName: String,
    val lastOpenedAt: Long,
) {
    fun toModel() = RecentFile(
        filePath = filePath,
        projectRoot = projectRoot,
        projectName = projectName,
        relativePath = relativePath,
        displayName = displayName,
        lastOpenedAt = lastOpenedAt,
    )
}
