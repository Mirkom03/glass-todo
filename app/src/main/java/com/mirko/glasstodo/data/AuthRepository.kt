package com.mirko.glasstodo.data

import com.mirko.glasstodo.domain.isTokenFresh
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
     * refreshes the token on foreground any more — WE own it. Called on every resume.
     */
    suspend fun refreshSession() = client.auth.refreshCurrentSession()

    /**
     * Refresh ONLY if the access token is missing or about to expire.
     *
     * The widget is the dangerous path: a tap can resurrect a dead process hours later, with an
     * expired token and no auto-refresh timer alive. Postgrest does not refresh for us — it falls
     * back to the anon key, and RLS then silently drops the write. But refreshing on every tap would
     * race Supabase's refresh-token rotation, so we look at the token's own `exp` first.
     */
    suspend fun ensureFreshSession() {
        client.auth.awaitInitialization()
        if (!isTokenFresh(client.auth.currentAccessTokenOrNull(), System.currentTimeMillis())) {
            client.auth.refreshCurrentSession()
        }
    }
}
