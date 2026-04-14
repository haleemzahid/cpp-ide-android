package dev.cppide.core.session.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface RecentFileDao {

    @Query(
        """
        SELECT * FROM recent_files
        ORDER BY lastOpenedAt DESC
        LIMIT :limit
        """
    )
    fun recent(limit: Int): Flow<List<RecentFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecentFileEntity)

    @Query("DELETE FROM recent_files WHERE filePath = :filePath")
    suspend fun delete(filePath: String)
}
