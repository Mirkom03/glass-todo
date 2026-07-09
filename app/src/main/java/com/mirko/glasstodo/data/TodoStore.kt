package com.mirko.glasstodo.data

import com.mirko.glasstodo.data.local.SyncStatus
import com.mirko.glasstodo.data.local.TodoDao
import com.mirko.glasstodo.data.local.TodoEntity
import com.mirko.glasstodo.data.local.toUi
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
        val rows = remote.list(uid)
        dao.upsertAll(rows.map { it.toEntity(SyncStatus.SYNCED) })
        dao.deleteMissing(rows.map { it.id })          // drop rows deleted elsewhere
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

    suspend fun delete(id: String) = withContext(io) {
        val prev = dao.byId(id) ?: return@withContext
        dao.upsert(prev.copy(deleted = true, syncStatus = SyncStatus.PENDING))                   // OPTIMISTIC soft-delete
        runCatching { remote.delete(id) }
            .onSuccess { dao.hardDelete(id) }
            .onFailure { err -> if (err.isPermanent()) dao.upsert(prev); throw err }             // ROLLBACK: reappears
    }

    private fun now() = System.currentTimeMillis()
}
