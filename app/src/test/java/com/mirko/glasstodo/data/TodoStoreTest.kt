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

    private class FakeRemote(var failStatus: Int? = null) : TodoRemote {
        val inserted = mutableListOf<TodoDto>()
        override suspend fun list(userId: String): List<TodoDto> = emptyList()
        override suspend fun insert(dto: TodoDto) { failStatus?.let { throw RemoteException(it) }; inserted += dto }
        override suspend fun setDone(id: String, done: Boolean) { failStatus?.let { throw RemoteException(it) } }
        override suspend fun delete(id: String) { failStatus?.let { throw RemoteException(it) } }
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
}
