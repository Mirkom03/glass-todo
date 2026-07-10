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

    // A push completion may only settle ITS OWN value. Writing back a pre-push snapshot resurrected
    // the old state over a newer tap («no puedo destickearlo», 2026-07-09) — hence the WHERE guards:
    // if the row no longer holds the value this push carried, a newer write owns it now.
    @Query("UPDATE todos SET syncStatus = 'SYNCED' WHERE id = :id AND done = :done AND syncStatus = 'PENDING'")
    suspend fun settleToggle(id: String, done: Boolean)

    @Query(
        """
        UPDATE todos SET done = :prevDone, syncStatus = :prevStatus
        WHERE id = :id AND done = :attempted AND syncStatus = 'PENDING'
        """
    )
    suspend fun rollbackToggle(id: String, attempted: Boolean, prevDone: Boolean, prevStatus: SyncStatus)

    // The same guard as settleToggle, widened to every field the detail sheet writes. `IS`, not `=`:
    // in SQLite `project = NULL` is never true, so `=` would never match a row whose tag was cleared.
    @Query(
        """
        UPDATE todos SET syncStatus = 'SYNCED'
        WHERE id = :id AND title = :title AND project IS :project
          AND priority = :priority AND notes IS :notes AND syncStatus = 'PENDING'
        """
    )
    suspend fun settleUpdate(id: String, title: String, project: String?, priority: Int, notes: String?)

    @Query(
        """
        UPDATE todos
           SET title = :prevTitle, project = :prevProject, priority = :prevPriority,
               notes = :prevNotes, syncStatus = :prevStatus
         WHERE id = :id AND title = :attemptedTitle AND project IS :attemptedProject
           AND priority = :attemptedPriority AND notes IS :attemptedNotes AND syncStatus = 'PENDING'
        """
    )
    suspend fun rollbackUpdate(
        id: String,
        prevTitle: String, prevProject: String?, prevPriority: Int, prevNotes: String?,
        prevStatus: SyncStatus,
        attemptedTitle: String, attemptedProject: String?, attemptedPriority: Int, attemptedNotes: String?,
    )

    @Query("DELETE FROM todos WHERE id = :id")
    suspend fun hardDelete(id: String)

    // PENDING rows are local writes the server hasn't seen yet (offline add / tombstone) — a server
    // snapshot doesn't contain them, so dropping them here would lose data; the sync drain pushes them.
    @Query("DELETE FROM todos WHERE id NOT IN (:keepIds) AND syncStatus != 'PENDING'")
    suspend fun deleteMissing(keepIds: List<String>)
}
