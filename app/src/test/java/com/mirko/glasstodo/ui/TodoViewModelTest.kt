package com.mirko.glasstodo.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.mirko.glasstodo.data.AuthSource
import com.mirko.glasstodo.data.TodoStore
import com.mirko.glasstodo.data.local.AppDatabase
import com.mirko.glasstodo.data.remote.TodoDto
import com.mirko.glasstodo.data.remote.TodoRemote
import com.mirko.glasstodo.domain.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TodoViewModelTest {

    private lateinit var db: AppDatabase
    private val auth = object : AuthSource { override suspend fun requireUid() = "u1" }

    private class FakeRemote(
        var serverRows: List<TodoDto> = emptyList(),
        var failStatus: Int? = null,
    ) : TodoRemote {
        override suspend fun list(userId: String): List<TodoDto> {
            failStatus?.let { throw RemoteException(it) }
            return serverRows
        }
        override suspend fun insert(dto: TodoDto) { failStatus?.let { throw RemoteException(it) } }
        override suspend fun setDone(id: String, done: Boolean) { failStatus?.let { throw RemoteException(it) } }
        override suspend fun delete(id: String) { failStatus?.let { throw RemoteException(it) } }
    }

    private fun vm(remote: TodoRemote) = TodoViewModel(
        TodoStore(db.todoDao(), remote, auth, io = Dispatchers.Unconfined)
    )

    private suspend fun <T> ReceiveTurbine<T>.awaitMatching(predicate: (T) -> Boolean): T {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After fun teardown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test fun firstFrameIsLoading_thenContent_neverBlank() = runTest {
        val remote = FakeRemote(serverRows = listOf(TodoDto(id = "1", user_id = "u1", title = "a")))
        val vm = vm(remote)
        // Before anything collects, the state a fresh screen renders is loading — NOT an empty list.
        assertTrue(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.isEmpty)
        vm.uiState.test {
            val content = awaitMatching { !it.isLoading && it.todos.size == 1 && !it.isSyncing }
            assertEquals("a", content.todos[0].title)
            assertFalse(content.isEmpty)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test fun refreshFailure_surfacesError_insteadOfBlankScreen() = runTest {
        val vm = vm(FakeRemote(failStatus = 503))                   // network down from the start
        vm.uiState.test {
            val errored = awaitMatching { it.errorMessage != null }
            assertNotNull(errored.errorMessage)
            vm.errorShown()
            awaitMatching { it.errorMessage == null }               // snackbar consumed → cleared
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test fun add_parsesProjectTag_andShowsOptimistically() = runTest {
        val vm = vm(FakeRemote())
        vm.uiState.test {
            awaitMatching { !it.isLoading }
            vm.add("Comprar pan #casa")
            val s = awaitMatching { it.todos.size == 1 }
            assertEquals("Comprar pan", s.todos[0].title)
            assertEquals("casa", s.todos[0].project)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test fun toggle_transientFailure_keepsOptimisticState_noErrorToast() = runTest {
        val remote = FakeRemote(serverRows = listOf(TodoDto(id = "1", user_id = "u1", title = "a")))
        val vm = vm(remote)
        vm.uiState.test {
            awaitMatching { it.todos.size == 1 && !it.isSyncing }
            remote.failStatus = 503                                 // go offline
            vm.toggle("1", true)
            val s = awaitMatching { it.todos.size == 1 && it.todos[0].done }
            assertTrue(s.todos[0].pending)                          // kept, marked pending
            assertEquals(null, s.errorMessage)                      // transient = silent
            cancelAndConsumeRemainingEvents()
        }
    }
}
