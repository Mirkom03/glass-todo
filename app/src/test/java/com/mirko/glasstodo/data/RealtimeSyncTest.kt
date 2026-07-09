package com.mirko.glasstodo.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirko.glasstodo.data.local.AppDatabase
import com.mirko.glasstodo.data.local.SyncStatus
import com.mirko.glasstodo.data.local.TodoEntity
import com.mirko.glasstodo.data.remote.TodoDto
import com.mirko.glasstodo.data.remote.TodoRemote
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RealtimeSyncTest {

    private lateinit var db: AppDatabase
    private val auth = object : AuthSource { override suspend fun requireUid() = "u1" }

    /** Counts pulls so we can prove a reconnect triggers exactly one gap-closing refresh. */
    private class CountingRemote(var serverRows: List<TodoDto> = emptyList()) : TodoRemote {
        var listCalls = 0
        override suspend fun list(userId: String): List<TodoDto> { listCalls++; return serverRows }
        override suspend fun insert(dto: TodoDto) = Unit
        override suspend fun setDone(id: String, done: Boolean) = Unit
        override suspend fun delete(id: String) = Unit
    }

    private fun dto(id: String, title: String = "t") = TodoDto(id = id, user_id = "u1", title = title)

    private fun sync(
        store: TodoStore,
        scope: CoroutineScope,
        authenticated: () -> Flow<Boolean> = { flowOf(true) },
        snapshots: () -> Flow<List<TodoDto>> = { emptyFlow() },
        status: () -> Flow<Realtime.Status> = { emptyFlow() },
        onSnapshot: suspend () -> Unit = {},
    ) = RealtimeSync(store, scope, authenticated, snapshots, status, onSnapshot)

    private fun storeWith(remote: TodoRemote, dispatcher: UnconfinedTestDispatcher) =
        TodoStore(db.todoDao(), remote, auth, io = dispatcher)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After fun teardown() = db.close()

    @Test fun snapshot_landsInRoom_andNotifies() = runTest {
        val d = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(d)
        val store = storeWith(CountingRemote(), d)
        var notified = 0

        sync(store, scope, snapshots = { flowOf(listOf(dto("1", "desde realtime"))) }, onSnapshot = { notified++ }).start()

        val rows = db.todoDao().observeAll().first()
        assertEquals(1, rows.size)
        assertEquals("desde realtime", rows[0].title)
        assertEquals(SyncStatus.SYNCED, rows[0].syncStatus)
        assertEquals(1, notified)                                  // widget/UI push fires per snapshot
        scope.cancel()
    }

    @Test fun signedOut_neverSubscribes_soRoomIsNotWiped() = runTest {
        db.todoDao().upsert(TodoEntity(id = "local", userId = "u1", title = "cache offline"))
        val d = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(d)
        val store = storeWith(CountingRemote(), d)
        var snapshotsSubscribed = 0

        // Anon RLS would return [] and reconcile would delete every synced row — must never happen.
        sync(store, scope, authenticated = { flowOf(false) }, snapshots = {
            snapshotsSubscribed++; flowOf(emptyList())
        }).start()

        assertEquals(0, snapshotsSubscribed)
        assertEquals(listOf("cache offline"), db.todoDao().observeAll().first().map { it.title })
        scope.cancel()
    }

    @Test fun signIn_startsSyncing() = runTest {
        val d = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(d)
        val store = storeWith(CountingRemote(), d)

        sync(store, scope, authenticated = { flowOf(false, true) },
            snapshots = { flowOf(listOf(dto("1", "tras login"))) }).start()

        assertEquals(listOf("tras login"), db.todoDao().observeAll().first().map { it.title })
        scope.cancel()
    }

    @Test fun snapshotFailure_keepsExistingRoomData_andDoesNotCrash() = runTest {
        db.todoDao().upsert(TodoEntity(id = "local", userId = "u1", title = "no me borres"))
        val d = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(d)
        val store = storeWith(CountingRemote(), d)

        sync(store, scope, snapshots = { flow { throw IllegalStateException("websocket boom") } }).start()

        assertEquals(listOf("no me borres"), db.todoDao().observeAll().first().map { it.title })
        scope.cancel()
    }

    @Test fun firstConnect_doesNotRefetch() = runTest {
        val d = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(d)
        val remote = CountingRemote()
        val store = storeWith(remote, d)

        sync(store, scope, status = { flowOf(Realtime.Status.CONNECTING, Realtime.Status.CONNECTED) }).start()

        assertEquals(0, remote.listCalls)                          // the snapshot flow's initial select covers it
        scope.cancel()
    }

    @Test fun reconnectAfterDrop_pullsOnce_toCloseTheGap() = runTest {
        val d = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(d)
        val remote = CountingRemote(serverRows = listOf(dto("1", "perdido durante la caida")))
        val store = storeWith(remote, d)

        sync(store, scope, status = {
            flowOf(
                Realtime.Status.CONNECTED,       // first connect: no pull
                Realtime.Status.DISCONNECTED,    // events missed here are never re-emitted
                Realtime.Status.CONNECTED,       // -> pull once
            )
        }).start()

        assertEquals(1, remote.listCalls)
        assertEquals("perdido durante la caida", db.todoDao().observeAll().first()[0].title)
        scope.cancel()
    }

    @Test fun disconnectBeforeAnyConnect_doesNotRefetch() = runTest {
        val d = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(d)
        val remote = CountingRemote()
        val store = storeWith(remote, d)

        sync(store, scope, status = { flowOf(Realtime.Status.DISCONNECTED, Realtime.Status.CONNECTED) }).start()

        assertEquals(0, remote.listCalls)                          // cold start is a connect, not a reconnect
        scope.cancel()
    }
}
