package dev.cppide.core.session.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RecentProjectEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class CoreDatabase : RoomDatabase() {

    abstract fun recentProjectDao(): RecentProjectDao

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
                ).build().also { INSTANCE = it }
            }
    }
}
