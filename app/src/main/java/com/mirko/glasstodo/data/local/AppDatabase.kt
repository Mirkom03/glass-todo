package com.mirko.glasstodo.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter fun fromSync(s: SyncStatus): String = s.name
    @TypeConverter fun toSync(s: String): SyncStatus = SyncStatus.valueOf(s)
}

/**
 * v1 -> v2 adds the task description. Additive and nullable, so no row is rewritten and every
 * existing task simply gets `notes = NULL`.
 *
 * `String?` in the entity makes Room expect a nullable TEXT column: the DDL here must say exactly
 * `TEXT` and never `TEXT NOT NULL`, or the identity-hash check fails when the database is opened.
 *
 * v1.3.3 shipped with no migrations registered at all, so forgetting this object would crash the app
 * on launch for every installed user («A migration from 1 to 2 was required but not found»).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `todos` ADD COLUMN `notes` TEXT")
    }
}

@Database(entities = [TodoEntity::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun todoDao(): TodoDao
}
