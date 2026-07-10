package com.mirko.glasstodo.di

import android.content.Context
import androidx.room.Room
import com.mirko.glasstodo.data.RealtimeSync
import com.mirko.glasstodo.data.SupabaseAuthSource
import com.mirko.glasstodo.data.SupabaseClient
import com.mirko.glasstodo.data.TodoStore
import androidx.glance.appwidget.updateAll
import com.mirko.glasstodo.data.local.AppDatabase
import com.mirko.glasstodo.data.local.MIGRATION_1_2
import com.mirko.glasstodo.data.remote.TodoRemoteImpl
import com.mirko.glasstodo.widget.TodoGlanceWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app and the widget run in the same process and MUST share one [TodoStore], so a write from
 * either lands in the same Room database that both observe.
 */
object ServiceLocator {

    @Volatile private var storeRef: TodoStore? = null

    /**
     * Process-lifetime scope. A write started from the detail sheet must survive the sheet's Activity
     * finishing — `lifecycleScope` would cancel it halfway, leaving Room optimistic and the server
     * untouched.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun store(context: Context): TodoStore = storeRef ?: synchronized(this) {
        storeRef ?: build(context.applicationContext).also { storeRef = it }
    }

    private fun build(ctx: Context): TodoStore {
        SupabaseClient.init()                       // idempotent
        val client = SupabaseClient.client
        // Without addMigrations, bumping the schema version crashes on open for every install coming
        // from v1.3.3. Never fallbackToDestructiveMigration: it would drop the PENDING rows, which are
        // the ONLY copy of a write the server has not seen yet.
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2)
            .build()
        val store = TodoStore(db.todoDao(), TodoRemoteImpl(client), SupabaseAuthSource(client))
        // One long-lived realtime collector for the whole process; it waits for an authenticated
        // session before subscribing, so a signed-out cold start never wipes the local cache.
        // Every snapshot also re-renders the widget, even when no app UI is on screen.
        RealtimeSync.from(client, store, appScope, onSnapshot = { TodoGlanceWidget().updateAll(ctx) }).start()
        return store
    }

    private const val DB_NAME = "todos.db"
}
