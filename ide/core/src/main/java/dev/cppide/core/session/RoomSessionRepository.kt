package dev.cppide.core.session

import android.content.Context
import dev.cppide.core.common.DispatcherProvider
import dev.cppide.core.session.db.CoreDatabase
import dev.cppide.core.session.db.RecentProjectDao
import dev.cppide.core.session.db.RecentProjectEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed [SessionRepository]. Owns a single [CoreDatabase] instance
 * per process via [CoreDatabase.get].
 */
class RoomSessionRepository(
    context: Context,
    private val dispatchers: DispatcherProvider,
) : SessionRepository {

    private val dao: RecentProjectDao = CoreDatabase.get(context).recentProjectDao()

    override fun recentProjects(limit: Int): Flow<List<RecentProject>> =
        dao.recent(limit).map { list -> list.map { it.toModel() } }

    override suspend fun touch(rootPath: String, displayName: String) =
        withContext(dispatchers.io) {
            dao.upsert(
                RecentProjectEntity(
                    rootPath = rootPath,
                    displayName = displayName,
                    lastOpenedAt = System.currentTimeMillis(),
                    pinned = false,
                )
            )
        }

    override suspend fun setPinned(rootPath: String, pinned: Boolean) =
        withContext(dispatchers.io) { dao.setPinned(rootPath, pinned) }

    override suspend fun forget(rootPath: String) =
        withContext(dispatchers.io) { dao.delete(rootPath) }
}
