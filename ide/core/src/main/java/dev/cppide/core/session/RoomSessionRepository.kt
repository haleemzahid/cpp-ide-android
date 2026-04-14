package dev.cppide.core.session

import android.content.Context
import dev.cppide.core.common.DispatcherProvider
import dev.cppide.core.session.db.CoreDatabase
import dev.cppide.core.session.db.RecentFileDao
import dev.cppide.core.session.db.RecentFileEntity
import dev.cppide.core.session.db.RecentProjectDao
import dev.cppide.core.session.db.RecentProjectEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomSessionRepository(
    context: Context,
    private val dispatchers: DispatcherProvider,
) : SessionRepository {

    private val db = CoreDatabase.get(context)
    private val projectDao: RecentProjectDao = db.recentProjectDao()
    private val fileDao: RecentFileDao = db.recentFileDao()

    override fun recentProjects(limit: Int): Flow<List<RecentProject>> =
        projectDao.recent(limit).map { list -> list.map { it.toModel() } }

    override suspend fun touch(rootPath: String, displayName: String) =
        withContext(dispatchers.io) {
            val canonical = java.io.File(rootPath).canonicalPath
            projectDao.upsert(
                RecentProjectEntity(
                    rootPath = canonical,
                    displayName = displayName,
                    lastOpenedAt = System.currentTimeMillis(),
                    pinned = false,
                )
            )
        }

    override suspend fun setPinned(rootPath: String, pinned: Boolean) =
        withContext(dispatchers.io) { projectDao.setPinned(rootPath, pinned) }

    override suspend fun forget(rootPath: String) =
        withContext(dispatchers.io) { projectDao.delete(rootPath) }

    // ---- recent files ----

    override fun recentFiles(limit: Int): Flow<List<RecentFile>> =
        fileDao.recent(limit).map { list -> list.map { it.toModel() } }

    override suspend fun touchFile(
        filePath: String,
        projectRoot: String,
        projectName: String,
        relativePath: String,
        displayName: String,
    ) = withContext(dispatchers.io) {
        fileDao.upsert(
            RecentFileEntity(
                filePath = filePath,
                projectRoot = projectRoot,
                projectName = projectName,
                relativePath = relativePath,
                displayName = displayName,
                lastOpenedAt = System.currentTimeMillis(),
            )
        )
    }
}
