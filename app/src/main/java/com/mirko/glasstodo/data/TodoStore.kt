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
import kotlinx.coroutines.CancellationException
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
        // PUSH before PULL. A pull treats the server as authoritative for everything except PENDING
        // rows, so if local writes were never sent they would sit here forever, invisible to every
        // other device and destroyed by the next reinstall.
        drainPending()
        reconcile(remote.list(uid))
    }

    /**
     * Replays every local write whose first push failed (offline add, toggle that hit a 503, a
     * delete made while the token was stale). Without this, [SyncStatus.PENDING] is a graveyard:
     * [reconcile] protects those rows from the server snapshot, so the two copies never reconverge.
     *
     * Uses `upsert` rather than insert-or-update because we cannot know whether the server already
     * saw the row: the first push may have reached Postgres and then lost the response.
     *
     * A PERMANENT failure (a 4xx that is not auth) means the server will never accept this row.
     * We drop tombstones — the delete is already reflected locally — but keep data rows PENDING and
     * visible (dimmed in the UI) rather than deleting something the user typed.
     *
     * @return how many rows reached the server.
     */
    suspend fun drainPending(): Int = withContext(io) {
        var pushed = 0
        for (row in dao.pending()) {
            val result = runCatching {
                if (row.deleted) {
                    remote.delete(row.id)
                    dao.hardDelete(row.id)
                } else {
                    remote.upsert(row.toDto())
                    dao.upsert(row.copy(syncStatus = SyncStatus.SYNCED))
                }
            }
            when {
                result.isSuccess -> pushed++
                else -> {
                    val error = result.exceptionOrNull()!!
                    if (error is CancellationException) throw error
                    if (error.isPermanent() && row.deleted) dao.hardDelete(row.id)
                    // transient (offline, 5xx, expired token): leave it PENDING, retry next drain
                }
            }
        }
        pushed
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
