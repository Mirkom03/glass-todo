package com.mirko.glasstodo.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mirko.glasstodo.domain.TodoUi

enum class SyncStatus { SYNCED, PENDING }

@Entity(tableName = "todos")
data class TodoEntity(
    @PrimaryKey val id: String,          // CLIENT UUID → offline creations exist before the server sees them
    val userId: String,
    val title: String,
    val project: String? = null,
    val priority: Int = 0,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,        // tombstone → soft delete, reappears on rollback
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
)

fun TodoEntity.toUi() = TodoUi(
    id = id,
    title = title,
    project = project,
    done = done,
    pending = syncStatus == SyncStatus.PENDING,
)
