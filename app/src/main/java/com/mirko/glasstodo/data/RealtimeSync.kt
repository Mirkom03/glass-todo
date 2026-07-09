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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * One long-lived collector: Supabase realtime → [TodoStore.reconcile] → Room. Replaces v1's
 * 15-min widget poll as the live path while the process is alive; TodoSyncWorker stays as the
 * safety net for the process-dead case.
 */
class RealtimeSync(
    private val client: KtClient,
    private val store: TodoStore,
    private val scope: CoroutineScope,              // app-lived scope, NOT a recomposing composable scope
    private val onSnapshot: suspend () -> Unit = {}, // step 8 wires the Glance widget's updateAll here
) {
    @OptIn(SupabaseExperimental::class)
    fun start(): Job = scope.launch {
        client.auth.awaitInitialization()           // subscribe with a real JWT, or RLS yields nothing
        launch { refetchOnReconnect() }
        // selectAsFlow = initial select + postgres_changes merged, keyed by PK. RLS scopes rows to
        // the logged-in user, no client filter needed.
        client.from("todos").selectAsFlow(TodoDto::id)
            .catch { /* initial-select/decode failure: keep Room as-is; worker refresh covers it */ }
            .collect { rows ->
                store.reconcile(rows)
                onSnapshot()
            }
    }

    /**
     * Socket drops do NOT error the flow: supabase-kt reconnects and re-subscribes silently, but
     * events missed during the outage are never re-emitted (verified vs 3.6.0 RealtimeImpl) — so a
     * reconnect triggers one full pull to close the gap.
     */
    private suspend fun refetchOnReconnect() {
        var wasDisconnected = false
        client.realtime.status.collect { status ->
            when (status) {
                Realtime.Status.DISCONNECTED -> wasDisconnected = true
                Realtime.Status.CONNECTED -> if (wasDisconnected) {
                    wasDisconnected = false
                    runCatching { store.refresh() }
                }
                else -> Unit
            }
        }
    }
}
