package com.mirko.glasstodo.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {
    // Pending first, most urgent first, newest first; everything done sinks to the bottom.
    // Ordering lives in SQL so the app and the widget can never disagree about it.
    @Query(
        """
        SELECT * FROM todos WHERE deleted = 0
        ORDER BY done ASC, priority DESC, createdAt DESC
        """
    )
    fun observeAll(): Flow<List<TodoEntity>>           // the ONLY read path for UI + widget

    @Query("SELECT * FROM todos WHERE syncStatus = 'PENDING'")
    suspend fun pending(): List<TodoEntity>            // the sync drain replays these

    /** The tags you actually use, most-used first — offered as one-tap chips when adding. */
    @Query(
        """
        SELECT project FROM todos
         WHERE deleted = 0 AND project IS NOT NULL AND TRIM(project) != ''
         GROUP BY project
         ORDER BY COUNT(*) DESC, MAX(createdAt) DESC
         LIMIT 8
        """
    )
    suspend fun topProjects(): List<String>

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun byId(id: String): TodoEntity?

    @Upsert suspend fun upsert(t: TodoEntity)
    @Upsert suspend fun upsertAll(t: List<TodoEntity>)

    @Query("DELETE FROM todos WHERE id = :id")
    suspend fun hardDelete(id: String)

    // PENDING rows are local writes the server hasn't seen yet (offline add / tombstone) — a server
    // snapshot doesn't contain them, so dropping them here would lose data; the sync drain pushes them.
    @Query("DELETE FROM todos WHERE id NOT IN (:keepIds) AND syncStatus != 'PENDING'")
    suspend fun deleteMissing(keepIds: List<String>)
}
