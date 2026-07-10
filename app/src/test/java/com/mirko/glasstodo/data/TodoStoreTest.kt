package com.mirko.glasstodo.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirko.glasstodo.data.local.AppDatabase
import com.mirko.glasstodo.data.local.SyncStatus
import com.mirko.glasstodo.data.local.TodoEntity
import com.mirko.glasstodo.data.remote.TodoDto
import com.mirko.glasstodo.data.remote.TodoRemote
import com.mirko.glasstodo.domain.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TodoStoreTest {

    private lateinit var db: AppDatabase
    private val auth = object : AuthSource { override suspend fun requireUid() = "u1" }

    /**
     * An in-memory stand-in for the server, not just a call recorder: a write that succeeds is
     * visible to the next `list()`. Otherwise the tests would lie about `refresh()`, which now
     * pushes before it pulls.
     */
    private class FakeRemote(
        var failStatus: Int? = null,
        serverRows: List<TodoDto> = emptyList(),
    ) : TodoRemote {
        val rows = serverRows.toMutableList()
        val inserted = mutableListOf<TodoDto>()
        val upserted = mutableListOf<TodoDto>()
        val updated = mutableListOf<TodoDto>()
        val deleted = mutableListOf<String>()
        val calls = mutableListOf<String>()          // ordering: proves push happens before pull

        private fun boom() = failStatus?.let { throw RemoteException(it) }

        override suspend fun list(userId: String): List<TodoDto> { calls += "list"; boom(); return rows.toList() }
        override suspend fun insert(dto: TodoDto) { calls += "insert"; boom(); inserted += dto; put(dto) }
        override suspend fun upsert(dto: TodoDto) { calls += "upsert"; boom(); upserted += dto; put(dto) }
        override suspend fun setDone(id: String, done: Boolean) {
            calls += "setDone"; boom()
            rows.replaceAll { if (it.id == id) it.copy(done = done) else it }
        }
        override suspend fun update(id: String, title: String, project: String?, priority: Int, notes: String?) {
            calls += "update"; boom()
            // A targeted UPDATE: it touches these four columns and nothing else — never `done`.
            rows.replaceAll {
                if (it.id == id) it.copy(title = title, project = project, priority = priority, notes = notes) else it
            }
            updated += TodoDto(id = id, user_id = "u1", title = title, project = project, priority = priority, notes = notes)
        }
        override suspend fun delete(id: String) { calls += "delete"; boom(); deleted += id; rows.removeAll { it.id == id } }

        private fun put(dto: TodoDto) { rows.removeAll { it.id == dto.id }; rows += dto }
    }

    private fun store(remote: TodoRemote) =
        TodoStore(db.todoDao(), remote, auth, io = Dispatchers.Unconfined)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After fun teardown() = db.close()

    @Test fun add_isOptimistic_visibleImmediately() = runTest {
        store(FakeRemote()).add("Comprar pan", "casa")
        val todos = db.todoDao().observeAll().first()
        assertEquals(1, todos.size)
        assertEquals("Comprar pan", todos[0].title)
        assertEquals("casa", todos[0].project)
    }

    @Test fun toggle_rollsBack_onPermanentError() = runTest {
        db.todoDao().upsert(TodoEntity(id = "1", userId = "u1", title = "a", done = false))
        runCatching { store(FakeRemote(failStatus = 400)).toggle("1", true) }
        assertEquals(false, db.todoDao().byId("1")!!.done)          // reverted, not left lying
    }

    @Test fun toggle_keepsOptimistic_onTransientError() = runTest {
        db.todoDao().upsert(TodoEntity(id = "1", userId = "u1", title = "a", done = false))
        runCatching { store(FakeRemote(failStatus = 503)).toggle("1", true) }
        val e = db.todoDao().byId("1")!!
        assertEquals(true, e.done)                                  // kept — WorkManager will retry
        assertEquals(SyncStatus.PENDING, e.syncStatus)
    }

    @Test fun toggle_onMissingRow_isANoOp() = runTest {
        store(FakeRemote()).toggle("no-existe", true)
        assertEquals(0, db.todoDao().observeAll().first().size)
    }

    /**
     * The field bug of 2026-07-09 («no puedo destickearlo»). Two taps in quick succession: the
     * check's push is still in flight when the uncheck lands. The check's success callback used to
     * write back its PRE-TAP snapshot (`prev.copy`), resurrecting done=true over the user's uncheck
     * — the row re-ticked itself. The completion of a push may only mark ITS OWN value as synced;
     * it must never overwrite a newer write.
     */
    @Test fun toggle_aSlowFirstPushDoesNotClobberASecondTap() = runTest {
        db.todoDao().upsert(TodoEntity(id = "1", userId = "u1", title = "a", done = false))
        val arrived = CompletableDeferred<Unit>()                   // tap 1's push reached the network
        val gate = CompletableDeferred<Unit>()                      // ...and hangs there until released
        val base = FakeRemote()
        val remote = object : TodoRemote by base {
            var first = true
            override suspend fun setDone(id: String, done: Boolean) {
                if (first) { first = false; arrived.complete(Unit); gate.await() }
                base.setDone(id, done)
            }
        }
        val s = store(remote)

        val slowCheck = launch { s.toggle("1", true) }              // tap 1: check
        arrived.await()                                             // its optimistic write is in Room, push in flight
        s.toggle("1", false)                                        // tap 2: uncheck — completes first
        gate.complete(Unit)                                         // now the check's push resolves
        slowCheck.join()

        val e = db.todoDao().byId("1")!!
        assertEquals(false, e.done)                                 // the user's LAST action wins
        assertEquals(SyncStatus.SYNCED, e.syncStatus)               // and it is settled, not left PENDING
    }

    /** A tap whose target the row already holds must not touch the network — re-taps converge. */
    @Test fun toggle_isIdempotent_whenTheRowAlreadyHoldsTheTarget() = runTest {
        db.todoDao().upsert(TodoEntity(id = "1", userId = "u1", title = "a", done = true, syncStatus = SyncStatus.SYNCED))
        val remote = FakeRemote()
        store(remote).toggle("1", true)
        assertEquals(emptyList<String>(), remote.calls)
        assertEquals(true, db.todoDao().byId("1")!!.done)
    }

    // --- ordering: pending first, most urgent first, newest first; done sinks to the bottom ---

    @Test fun observeAll_ordersPendingByUrgencyAndPushesDoneToTheEnd() = runTest {
        val dao = db.todoDao()
        dao.upsert(TodoEntity(id = "vieja", userId = "u1", title = "vieja", priority = 0, createdAt = 100))
        dao.upsert(TodoEntity(id = "nueva", userId = "u1", title = "nueva", priority = 0, createdAt = 200))
        dao.upsert(TodoEntity(id = "urgente", userId = "u1", title = "urgente", priority = 2, createdAt = 50))
        dao.upsert(TodoEntity(id = "media", userId = "u1", title = "media", priority = 1, createdAt = 10))
        dao.upsert(TodoEntity(id = "hecha-urgente", userId = "u1", title = "hecha-urgente", priority = 3, done = true, createdAt = 300))
        dao.upsert(TodoEntity(id = "hecha", userId = "u1", title = "hecha", priority = 0, done = true, createdAt = 400))

        assertEquals(
            // urgency wins among the pending ones; ties break on newest; done last, whatever its urgency
            listOf("urgente", "media", "nueva", "vieja", "hecha-urgente", "hecha"),
            dao.observeAll().first().map { it.title }
        )
    }

    @Test fun observeAll_exposesPriorityToTheUi() = runTest {
        db.todoDao().upsert(TodoEntity(id = "1", userId = "u1", title = "a", priority = 2))
        assertEquals(2, store(FakeRemote()).observeTodos().first()[0].priority)
    }

    @Test fun add_persistsTheChosenUrgency_andSendsIt() = runTest {
        val remote = FakeRemote()
        store(remote).add("Llamar a José", "hacienda-verde", priority = 2)

        val row = db.todoDao().observeAll().first().single()
        assertEquals(2, row.priority)
        assertEquals("hacienda-verde", row.project)
        assertEquals(2, remote.inserted.single().priority)   // urgency reaches the server, not just Room
    }

    // --- tag suggestions: the tags you actually use, most-used first ---

    @Test fun tagSuggestions_rankByUseAndIgnoreEmptyOnes() = runTest {
        val dao = db.todoDao()
        dao.upsert(TodoEntity(id = "1", userId = "u1", title = "a", project = "aide"))
        dao.upsert(TodoEntity(id = "2", userId = "u1", title = "b", project = "aide"))
        dao.upsert(TodoEntity(id = "3", userId = "u1", title = "c", project = "aide"))
        dao.upsert(TodoEntity(id = "4", userId = "u1", title = "d", project = "casa"))
        dao.upsert(TodoEntity(id = "5", userId = "u1", title = "e", project = "casa"))
        dao.upsert(TodoEntity(id = "6", userId = "u1", title = "f", project = "makroa"))
        dao.upsert(TodoEntity(id = "7", userId = "u1", title = "g", project = null))
        dao.upsert(TodoEntity(id = "8", userId = "u1", title = "h", project = "   "))

        assertEquals(listOf("aide", "casa", "makroa"), store(FakeRemote()).tagSuggestions())
    }

    @Test fun tagSuggestions_ignoreDeletedRows() = runTest {
        db.todoDao().upsert(TodoEntity(id = "1", userId = "u1", title = "a", project = "muerta", deleted = true))
        assertEquals(emptyList<String>(), store(FakeRemote()).tagSuggestions())
    }

    // --- drainPending(): the regression tests for the data-loss bug (PENDING was a graveyard) ---

    @Test fun drain_pushesAnAddThatWasMadeOffline() = runTest {
        val remote = FakeRemote(failStatus = 503)                   // offline
        runCatching { store(remote).add("comprado offline", "casa") }
        assertEquals(SyncStatus.PENDING, db.todoDao().observeAll().first()[0].syncStatus)

        remote.failStatus = null                                    // back online
        assertEquals(1, store(remote).drainPending())

        assertEquals(listOf("comprado offline"), remote.upserted.map { it.title })
        assertEquals(SyncStatus.SYNCED, db.todoDao().observeAll().first()[0].syncStatus)
    }

    @Test fun drain_replaysATombstoneAndThenHardDeletesIt() = runTest {
        db.todoDao().upsert(TodoEntity(id = "1", userId = "u1", title = "a"))
        val remote = FakeRemote(failStatus = 503)
        runCatching { store(remote).delete("1") }
        assertEquals(true, db.todoDao().byId("1")!!.deleted)        // soft-deleted, still there

        remote.failStatus = null
        assertEquals(1, store(remote).drainPending())

        assertEquals(listOf("1"), remote.deleted)
        assertEquals(null, db.todoDao().byId("1"))                  // gone locally too
    }

    @Test fun drain_keepsRowPendingOnTransientFailure_andRetriesLater() = runTest {
        val remote = FakeRemote(failStatus = 503)
        runCatching { store(remote).add("sigue offline", null) }

        assertEquals(0, store(remote).drainPending())               // still offline
        assertEquals(SyncStatus.PENDING, db.todoDao().observeAll().first()[0].syncStatus)

        remote.failStatus = null
        assertEquals(1, store(remote).drainPending())               // and it eventually lands
        assertEquals(SyncStatus.SYNCED, db.todoDao().observeAll().first()[0].syncStatus)
    }

    @Test fun drain_dropsATombstoneTheServerPermanentlyRejects() = runTest {
        db.todoDao().upsert(TodoEntity(id = "1", userId = "u1", title = "a"))
        val remote = FakeRemote(failStatus = 503)
        runCatching { store(remote).delete("1") }

        remote.failStatus = 400                                     // permanent data failure
        assertEquals(0, store(remote).drainPending())
        assertEquals(null, db.todoDao().byId("1"))                  // do not retry a delete forever
    }

    @Test fun drain_keepsARowTheServerPermanentlyRejects_ratherThanLosingWhatTheUserTyped() = runTest {
        val remote = FakeRemote(failStatus = 503)
        runCatching { store(remote).add("texto del usuario", null) }

        remote.failStatus = 400                                     // permanent
        assertEquals(0, store(remote).drainPending())

        val rows = db.todoDao().observeAll().first()
        assertEquals(listOf("texto del usuario"), rows.map { it.title })
        assertEquals(SyncStatus.PENDING, rows[0].syncStatus)
    }

    @Test fun refresh_drainsBeforePulling() = runTest {
        val remote = FakeRemote(failStatus = 503)
        runCatching { store(remote).add("pendiente", null) }
        remote.failStatus = null
        remote.calls.clear()

        store(remote).refresh()

        // A pull first would treat the server as authoritative while the local write was still unsent.
        assertEquals(listOf("upsert", "list"), remote.calls)
    }

    // --- refresh() reconciliation: local PENDING writes must survive a server snapshot ---

    private fun serverDto(id: String, done: Boolean = false) =
        TodoDto(id = id, user_id = "u1", title = "server-$id", done = done)

    @Test fun refresh_pushesTheOfflineAddAndKeepsBothRows() = runTest {
        val remote = FakeRemote(failStatus = 503)                   // offline: add stays PENDING
        val s = store(remote)
        runCatching { s.add("creado offline", null) }
        remote.failStatus = null
        remote.rows += serverDto("srv-1")                           // the server only knows its own row

        s.refresh()                                                 // drains, then pulls

        val local = db.todoDao().observeAll().first()
        assertEquals(setOf("creado offline", "server-srv-1"), local.map { it.title }.toSet())
        assertEquals(SyncStatus.SYNCED, local.first { it.title == "creado offline" }.syncStatus)
        assertEquals(listOf("creado offline"), remote.upserted.map { it.title })   // it really was sent
    }

    @Test fun refresh_pushesAPendingToggleInsteadOfLettingTheServerRevertIt() = runTest {
        db.todoDao().upsert(TodoEntity(id = "1", userId = "u1", title = "a", done = false))
        val remote = FakeRemote(failStatus = 503, serverRows = listOf(serverDto("1", done = false)))
        val s = store(remote)
        runCatching { s.toggle("1", true) }                         // optimistic toggle, push failed
        remote.failStatus = null

        s.refresh()

        val e = db.todoDao().byId("1")!!
        assertEquals(true, e.done)                                  // the local write won...
        assertEquals(SyncStatus.SYNCED, e.syncStatus)               // ...and it reached the server
        assertEquals(true, remote.rows.single { it.id == "1" }.done)
    }

    @Test fun refresh_dropsSyncedRowsDeletedElsewhere() = runTest {
        db.todoDao().upsert(TodoEntity(id = "gone", userId = "u1", title = "x", syncStatus = SyncStatus.SYNCED))
        val s = store(FakeRemote(serverRows = listOf(serverDto("kept"))))
        s.refresh()
        val ids = db.todoDao().observeAll().first().map { it.id }
        assertEquals(listOf("kept"), ids)
    }

    // --- update(): editar título / etiqueta / urgencia / descripción desde la hoja de detalle ---

    private fun seed(notes: String? = null) = TodoEntity(
        id = "1", userId = "u1", title = "viejo", project = "casa", priority = 0,
        notes = notes, syncStatus = SyncStatus.SYNCED,
    )

    @Test fun update_isOptimistic_visibleBeforeTheServerAnswers() = runTest {
        db.todoDao().upsert(seed())
        val remote = FakeRemote()
        store(remote).update("1", "nuevo", "aide", 2, "una descripción")

        val e = db.todoDao().byId("1")!!
        assertEquals("nuevo", e.title)
        assertEquals("aide", e.project)
        assertEquals(2, e.priority)
        assertEquals("una descripción", e.notes)
        assertEquals(SyncStatus.SYNCED, e.syncStatus)     // settled: el push fue bien
        assertEquals(listOf("update"), remote.calls)
    }

    @Test fun update_clearsANoteAndATag_sendingRealNulls() = runTest {
        db.todoDao().upsert(seed(notes = "algo que borrar"))
        val remote = FakeRemote()
        store(remote).update("1", "viejo", null, 0, null)

        val e = db.todoDao().byId("1")!!
        assertNull(e.notes)
        assertNull(e.project)
        assertNull(remote.updated.single().notes)
        assertNull(remote.updated.single().project)
    }

    @Test fun update_rollsBack_onPermanentError() = runTest {
        db.todoDao().upsert(seed(notes = "original"))
        runCatching { store(FakeRemote(failStatus = 400)).update("1", "nuevo", "aide", 2, "cambiada") }

        val e = db.todoDao().byId("1")!!
        assertEquals("viejo", e.title)                    // revertido campo a campo
        assertEquals("casa", e.project)
        assertEquals(0, e.priority)
        assertEquals("original", e.notes)
        assertEquals(SyncStatus.SYNCED, e.syncStatus)     // y con el estado de sync que tenía antes
    }

    @Test fun update_keepsOptimistic_onTransientError() = runTest {
        db.todoDao().upsert(seed())
        runCatching { store(FakeRemote(failStatus = 503)).update("1", "nuevo", "casa", 0, "n") }

        val e = db.todoDao().byId("1")!!
        assertEquals("nuevo", e.title)                    // se conserva — el drain lo reintenta
        assertEquals(SyncStatus.PENDING, e.syncStatus)
    }

    @Test fun update_onMissingRow_isANoOp() = runTest {
        val remote = FakeRemote()
        store(remote).update("no-existe", "x", null, 0, null)
        assertEquals(emptyList<String>(), remote.calls)
    }

    @Test fun update_isANoOp_whenNothingChanged() = runTest {
        db.todoDao().upsert(seed(notes = "igual"))
        val remote = FakeRemote()
        store(remote).update("1", "viejo", "casa", 0, "igual")
        assertEquals(emptyList<String>(), remote.calls)   // no se toca la red por una edición vacía
    }

    /**
     * The 2026-07-09 lesson, applied to edits: a push that completes late may only settle ITS OWN
     * value. Here the first edit's push hangs while a second edit lands; when it resolves it must not
     * mark the row SYNCED, because the row no longer holds what that push carried.
     */
    @Test fun update_aSlowFirstPushDoesNotClobberASecondEdit() = runTest {
        db.todoDao().upsert(seed())
        val arrived = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val base = FakeRemote()
        val remote = object : TodoRemote by base {
            var first = true
            override suspend fun update(id: String, title: String, project: String?, priority: Int, notes: String?) {
                if (first) { first = false; arrived.complete(Unit); gate.await() }
                base.update(id, title, project, priority, notes)
            }
        }
        val s = store(remote)

        val slow = launch { s.update("1", "primera", "casa", 0, null) }
        arrived.await()
        s.update("1", "segunda", "casa", 0, null)         // la segunda edición gana
        gate.complete(Unit)
        slow.join()

        val e = db.todoDao().byId("1")!!
        assertEquals("segunda", e.title)                  // la última acción del usuario manda
        assertEquals(SyncStatus.SYNCED, e.syncStatus)
    }
}
