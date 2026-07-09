package com.mirko.glasstodo.data

import com.mirko.glasstodo.data.local.SyncStatus
import com.mirko.glasstodo.data.local.TodoDao
import com.mirko.glasstodo.data.local.TodoEntity
import com.mirko.glasstodo.data.local.toUi
import com.mirko.glasstodo.data.remote.TodoDto
import com.mirko.glasstodo.data.remote.TodoRemote
import com.mirko.glasstodo.data.remote.toDto
import com.mirko.glasstodo.data.remote.toEntity
import com.mirko.glasstodo.domain.TodoUi
import com.mirko.glasstodo.domain.isPermanent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Offline-first single source of truth. Every write lands in Room FIRST (optimistic, so the UI and
 * widget react instantly via the Flow), then pushes to Supabase; permanent failures roll back.
 */
class TodoStore(
    private val dao: TodoDao,
    private val remote: TodoRemote,
    private val auth: AuthSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    fun observeTodos(): Flow<List<TodoUi>> = dao.observeAll().map { list -> list.map { it.toUi() } }

    suspend fun refresh() = withContext(io) {
        val uid = auth.requireUid()
        reconcile(remote.list(uid))
    }

    /**
     * Merge a full server snapshot into Room. Local PENDING rows WIN: an unpushed offline add is
     * absent from the snapshot (deleting it would lose data) and an unpushed toggle/tombstone must
     * not be clobbered by the server's stale copy. Also the entry point for realtime snapshots.
     */
    internal suspend fun reconcile(rows: List<TodoDto>) {
        val pendingIds = dao.pending().map { it.id }.toSet()
        dao.upsertAll(rows.filter { it.id !in pendingIds }.map { it.toEntity(SyncStatus.SYNCED) })
        dao.deleteMissing(rows.map { it.id })          // the SQL itself keeps PENDING rows
    }

    suspend fun add(title: String, project: String?) = withContext(io) {
        val e = TodoEntity(
            id = UUID.randomUUID().toString(),
            userId = auth.requireUid(),
            title = title,
            project = project,
            syncStatus = SyncStatus.PENDING,
        )
        dao.upsert(e)                                   // (1) OPTIMISTIC — visible instantly via Flow
        runCatching { remote.insert(e.toDto()) }
            .onSuccess { dao.upsert(e.copy(syncStatus = SyncStatus.SYNCED)) }
            .onFailure { throw it }                     // stays PENDING → WorkManager retries
    }

    suspend fun toggle(id: String, done: Boolean) = withContext(io) {
        val prev = dao.byId(id) ?: return@withContext
        dao.upsert(prev.copy(done = done, syncStatus = SyncStatus.PENDING, updatedAt = now()))  // OPTIMISTIC
        runCatching { remote.setDone(id, done) }
            .onSuccess { dao.upsert(prev.copy(done = done, syncStatus = SyncStatus.SYNCED)) }
            .onFailure { err -> if (err.isPermanent()) dao.upsert(prev); throw err }             // ROLLBACK
    }

    /**
     * Flip whatever Room currently holds. The widget uses this: a widget render can be seconds old,
     * so the new value must be derived from the source of truth at tap time, never from the value
     * that was baked into the rendered row.
     */
    suspend fun toggle(id: String) {
        val prev = withContext(io) { dao.byId(id) } ?: return
        toggle(id, !prev.done)
    }

    suspend fun delete(id: String) = withContext(io) {
        val prev = dao.byId(id) ?: return@withContext
        dao.upsert(prev.copy(deleted = true, syncStatus = SyncStatus.PENDING))                   // OPTIMISTIC soft-delete
        runCatching { remote.delete(id) }
            .onSuccess { dao.hardDelete(id) }
            .onFailure { err -> if (err.isPermanent()) dao.upsert(prev); throw err }             // ROLLBACK: reappears
    }

    private fun now() = System.currentTimeMillis()
}
