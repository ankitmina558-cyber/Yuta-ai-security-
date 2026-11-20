package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ModerationLogEntity::class,
        ProcessedMessageEntity::class,
        StickerQueueEntity::class,
        ChatMessageEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class YutaDatabase : RoomDatabase() {
    abstract fun yutaDao(): YutaDao

    companion object {
        @Volatile
        private var INSTANCE: YutaDatabase? = null

        fun getInstance(context: Context): YutaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    YutaDatabase::class.java,
                    "yuta_ai_sqlite.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
