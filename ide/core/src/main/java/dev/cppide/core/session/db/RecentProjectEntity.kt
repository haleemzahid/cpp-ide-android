package dev.cppide.core.session.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.cppide.core.session.RecentProject

@Entity(tableName = "recent_projects")
internal data class RecentProjectEntity(
    @PrimaryKey val rootPath: String,
    val displayName: String,
    val lastOpenedAt: Long,
    val pinned: Boolean,
) {
    fun toModel() = RecentProject(rootPath, displayName, lastOpenedAt, pinned)

    companion object {
        fun from(model: RecentProject) = RecentProjectEntity(
            rootPath = model.rootPath,
            displayName = model.displayName,
            lastOpenedAt = model.lastOpenedAt,
            pinned = model.pinned,
        )
    }
}
