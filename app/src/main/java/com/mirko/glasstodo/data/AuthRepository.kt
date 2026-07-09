package com.mirko.glasstodo.data

import io.github.jan.supabase.SupabaseClient as KtClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.StateFlow

/** Everything the UI needs from Auth. Replaces the auth half of the v1 `TodoRepository`. */
class AuthRepository(private val client: KtClient = SupabaseClient.client) {

    val sessionStatus: StateFlow<SessionStatus> get() = client.auth.sessionStatus

    suspend fun signIn(email: String, password: String) =
        client.auth.signInWith(Email) { this.email = email; this.password = password }

    /**
     * We disabled `enableLifecycleCallbacks` (that default is what emptied the widget), so nothing
     * refreshes the token on foreground any more — WE own it. Called on every resume, and by
     * TodoSyncWorker for the app-process-dead case.
     */
    suspend fun refreshSession() = client.auth.refreshCurrentSession()
}
