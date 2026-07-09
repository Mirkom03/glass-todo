package com.mirko.glasstodo.data

import com.mirko.glasstodo.data.remote.TodoDto
import io.github.jan.supabase.SupabaseClient as KtClient
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.selectAsFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * One long-lived collector: Supabase realtime → [TodoStore.reconcile] → Room. Replaces v1's 15-min
 * widget poll as the live path while the process is alive; TodoSyncWorker stays as the safety net
 * for the process-dead case.
 *
 * The Supabase types are injected as plain flows ([snapshots], [connectionStatus]) so the sync
 * policy is unit-testable on the JVM without a websocket. Build the real one with [RealtimeSync.from].
 */
class RealtimeSync(
    private val store: TodoStore,
    private val scope: CoroutineScope,               // app-lived scope, NOT a recomposing composable scope
    private val awaitAuth: suspend () -> Unit,
    private val snapshots: () -> Flow<List<TodoDto>>,
    private val connectionStatus: () -> Flow<Realtime.Status>,
    private val onSnapshot: suspend () -> Unit = {}, // step 8 wires the Glance widget's updateAll here
) {
    fun start(): Job = scope.launch {
        awaitAuth()                                   // subscribe with a real JWT, or RLS yields nothing
        launch { refetchOnReconnect() }
        snapshots()
            .catch { /* initial-select/decode failure: keep Room as-is, the worker refresh covers it */ }
            .collect { rows ->
                store.reconcile(rows)
                onSnapshot()
            }
    }

    /**
     * Socket drops do NOT error the flow: supabase-kt reconnects and re-subscribes silently, but the
     * events missed during the outage are never re-emitted (verified against 3.6.0 RealtimeImpl) — so
     * a reconnect triggers one full pull to close the gap. The first connect is skipped: the snapshot
     * flow already ships an initial select with it.
     */
    private suspend fun refetchOnReconnect() {
        var everConnected = false
        var droppedAfterConnect = false
        connectionStatus().collect { status ->
            when (status) {
                Realtime.Status.DISCONNECTED -> if (everConnected) droppedAfterConnect = true
                Realtime.Status.CONNECTED -> {
                    if (droppedAfterConnect) {
                        droppedAfterConnect = false
                        runCatching { store.refresh() }
                    }
                    everConnected = true
                }
                else -> Unit
            }
        }
    }

    companion object {
        /** Wires the real Supabase client. `selectAsFlow` = initial select + postgres_changes, keyed by PK. */
        @OptIn(SupabaseExperimental::class)
        fun from(
            client: KtClient,
            store: TodoStore,
            scope: CoroutineScope,
            onSnapshot: suspend () -> Unit = {},
        ) = RealtimeSync(
            store = store,
            scope = scope,
            awaitAuth = { client.auth.awaitInitialization() },
            // RLS scopes the rows to the logged-in user, so no client-side filter is needed.
            snapshots = { client.from("todos").selectAsFlow(TodoDto::id) },
            connectionStatus = { client.realtime.status },
            onSnapshot = onSnapshot,
        )
    }
}
