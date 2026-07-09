package com.mirko.glasstodo.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun fromSync(s: SyncStatus): String = s.name
    @TypeConverter fun toSync(s: String): SyncStatus = SyncStatus.valueOf(s)
}

@Database(entities = [TodoEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun todoDao(): TodoDao
}
