package com.dev.nudge.data.session

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [FocusSessionEntity::class, SessionSegmentEntity::class],
    version = 1,
    exportSchema = false
)
abstract class NudgeDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile
        private var instance: NudgeDatabase? = null

        fun getInstance(context: Context): NudgeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NudgeDatabase::class.java,
                    "nudge.db"
                ).build().also { instance = it }
            }
    }
}
