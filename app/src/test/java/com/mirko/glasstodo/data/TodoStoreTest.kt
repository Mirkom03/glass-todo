package com.mirko.glasstodo.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirko.glasstodo.data.local.AppDatabase
import com.mirko.glasstodo.data.local.SyncStatus
import com.mirko.glasstodo.data.local.TodoEntity
import com.mirko.glasstodo.data.remote.TodoDto
import com.mirko.glasstodo.data.remote.TodoRemote
import com.mirko.glasstodo.domain.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test fun toggleFlip_derivesTheNewValueFromRoom_notFromTheCaller() = runTest {
        db.todoDao().upsert(TodoEntity(id = "1", userId = "u1", title = "a", done = false))
        val s = store(FakeRemote())
        s.toggle("1")                                               // widget path: no value passed in
        assertEquals(true, db.todoDao().byId("1")!!.done)
        s.toggle("1")
        assertEquals(false, db.todoDao().byId("1")!!.done)
    }

    @Test fun toggleFlip_onMissingRow_isANoOp() = runTest {
        store(FakeRemote()).toggle("no-existe")
        assertEquals(0, db.todoDao().observeAll().first().size)
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
}
