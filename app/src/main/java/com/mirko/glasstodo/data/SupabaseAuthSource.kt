package com.mirko.glasstodo.data

import com.mirko.glasstodo.domain.RemoteException
import io.github.jan.supabase.SupabaseClient as KtClient
import io.github.jan.supabase.auth.auth

/** Real [AuthSource] backed by the shared supabase-kt client. */
class SupabaseAuthSource(private val client: KtClient) : AuthSource {
    override suspend fun requireUid(): String {
        client.auth.awaitInitialization()   // session loads async from storage; don't read before it's ready
        return client.auth.currentUserOrNull()?.id
            ?: throw RemoteException(status = 401, message = "No authenticated session")
    }
}
