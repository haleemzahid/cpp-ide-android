package dev.cppide.core.session

import kotlinx.coroutines.flow.Flow

interface SessionRepository {

    fun recentProjects(limit: Int = 20): Flow<List<RecentProject>>

    suspend fun touch(rootPath: String, displayName: String)

    suspend fun setPinned(rootPath: String, pinned: Boolean)

    suspend fun forget(rootPath: String)

    // ---- recent files ----

    fun recentFiles(limit: Int = 15): Flow<List<RecentFile>>

    suspend fun touchFile(
        filePath: String,
        projectRoot: String,
        projectName: String,
        relativePath: String,
        displayName: String,
    )
}
