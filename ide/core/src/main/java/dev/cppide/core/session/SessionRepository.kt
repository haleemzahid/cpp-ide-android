package dev.cppide.core.session

import kotlinx.coroutines.flow.Flow

/**
 * Persisted IDE session state: recent projects, last-open files per project,
 * cursor positions. The UI should call [touch] whenever the user opens a
 * project and query [recentProjects] on app start.
 */
interface SessionRepository {

    /** Recent projects, ordered by [RecentProject.lastOpenedAt] desc. Hot stream. */
    fun recentProjects(limit: Int = 20): Flow<List<RecentProject>>

    /** Insert-or-update a recent entry (bumps its [RecentProject.lastOpenedAt]). */
    suspend fun touch(rootPath: String, displayName: String)

    /** Pin/unpin so an entry never falls out of the recents list. */
    suspend fun setPinned(rootPath: String, pinned: Boolean)

    /** Remove an entry (user clicked "Remove from recents"). */
    suspend fun forget(rootPath: String)
}
