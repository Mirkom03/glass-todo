package com.mirko.glasstodo.data.remote

import com.mirko.glasstodo.domain.RemoteException
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order

/**
 * Real [TodoRemote] on supabase-kt Postgrest (replaces the hand-rolled OkHttp REST of v1).
 * HTTP errors surface as [RemoteException] with the status code so the store can decide
 * rollback (permanent 4xx) vs keep-and-retry (auth/transient); network/timeout exceptions
 * propagate untouched — [com.mirko.glasstodo.domain.isPermanent] treats them as transient.
 */
class TodoRemoteImpl(private val client: SupabaseClient) : TodoRemote {

    override suspend fun list(userId: String): List<TodoDto> = remote {
        client.from(TABLE).select {
            filter { eq("user_id", userId) }
            order("created_at", Order.DESCENDING)
        }.decodeList<TodoDto>()
    }

    override suspend fun insert(dto: TodoDto) {
        remote { client.from(TABLE).insert(dto) }
    }

    override suspend fun setDone(id: String, done: Boolean) {
        remote {
            client.from(TABLE).update({ set("done", done) }) {
                filter { eq("id", id) }
            }
        }
    }

    override suspend fun delete(id: String) {
        remote {
            client.from(TABLE).delete {
                filter { eq("id", id) }
            }
        }
    }

    private inline fun <T> remote(block: () -> T): T = try {
        block()
    } catch (e: RestException) {
        throw RemoteException(status = e.statusCode, message = e.message, cause = e)
    }

    private companion object {
        const val TABLE = "todos"
    }
}
