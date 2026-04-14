package dev.cppide.core.session.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RecentProjectEntity::class, RecentFileEntity::class],
    version = 2,
    exportSchema = false,
)
internal abstract class CoreDatabase : RoomDatabase() {

    abstract fun recentProjectDao(): RecentProjectDao
    abstract fun recentFileDao(): RecentFileDao

    companion object {
        private const val NAME = "cppide-core.db"

        @Volatile
        private var INSTANCE: CoreDatabase? = null

        fun get(context: Context): CoreDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CoreDatabase::class.java,
                    NAME,
                ).fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
