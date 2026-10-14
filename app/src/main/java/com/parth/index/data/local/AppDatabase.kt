package com.parth.index.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.parth.index.data.model.ChatMessageEntity
import com.parth.index.data.model.DeadlineEntity
import com.parth.index.data.model.MaterialFileEntity
import com.parth.index.data.model.MaterialRequestEntity
import com.parth.index.data.model.NoticeEntity
import com.parth.index.data.model.SubjectEntity

/** The on-phone database (Room is just a tidy layer over SQLite, the
 *  database built into every Android phone). In local mode this IS the
 *  backend. When Firebase is wired in, the repository swaps where reads and
 *  writes go — this database stays as the offline cache. */
@Database(
    entities = [
        SubjectEntity::class,
        MaterialFileEntity::class,
        DeadlineEntity::class,
        ChatMessageEntity::class,
        NoticeEntity::class,
        MaterialRequestEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun subjectDao(): SubjectDao
    abstract fun fileDao(): FileDao
    abstract fun deadlineDao(): DeadlineDao
    abstract fun chatDao(): ChatDao
    abstract fun noticeDao(): NoticeDao
    abstract fun requestDao(): RequestDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "index.db"
                ).build().also { instance = it }
            }
    }
}
