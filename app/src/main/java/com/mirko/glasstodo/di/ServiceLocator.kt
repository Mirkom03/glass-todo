package com.mirko.glasstodo.di

import android.content.Context
import androidx.room.Room
import com.mirko.glasstodo.data.RealtimeSync
import com.mirko.glasstodo.data.SupabaseAuthSource
import com.mirko.glasstodo.data.SupabaseClient
import com.mirko.glasstodo.data.TodoStore
import com.mirko.glasstodo.data.local.AppDatabase
import com.mirko.glasstodo.data.remote.TodoRemoteImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app and the widget run in the same process and MUST share one [TodoStore], so a write from
 * either lands in the same Room database that both observe.
 */
object ServiceLocator {

    @Volatile private var storeRef: TodoStore? = null
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun store(context: Context): TodoStore = storeRef ?: synchronized(this) {
        storeRef ?: build(context.applicationContext).also { storeRef = it }
    }

    private fun build(ctx: Context): TodoStore {
        SupabaseClient.init()                       // idempotent
        val client = SupabaseClient.client
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, DB_NAME).build()
        val store = TodoStore(db.todoDao(), TodoRemoteImpl(client), SupabaseAuthSource(client))
        // One long-lived realtime collector for the whole process; it waits for an authenticated
        // session before subscribing, so a signed-out cold start never wipes the local cache.
        RealtimeSync.from(client, store, appScope).start()
        return store
    }

    private const val DB_NAME = "todos.db"
}
