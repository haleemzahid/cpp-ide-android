package dev.cppide.core.session.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface RecentProjectDao {

    @Query(
        """
        SELECT * FROM recent_projects
        ORDER BY pinned DESC, lastOpenedAt DESC
        LIMIT :limit
        """
    )
    fun recent(limit: Int): Flow<List<RecentProjectEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecentProjectEntity)

    @Query("UPDATE recent_projects SET pinned = :pinned WHERE rootPath = :rootPath")
    suspend fun setPinned(rootPath: String, pinned: Boolean)

    @Query("DELETE FROM recent_projects WHERE rootPath = :rootPath")
    suspend fun delete(rootPath: String)
}
