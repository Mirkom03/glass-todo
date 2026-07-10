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
        remote {
            requireAffected(client.from(TABLE).insert(dto) { select() }.decodeList<TodoDto>(), "insert")
        }
    }

    override suspend fun upsert(dto: TodoDto) {
        remote {
            requireAffected(client.from(TABLE).upsert(dto) { select() }.decodeList<TodoDto>(), "upsert")
        }
    }

    override suspend fun setDone(id: String, done: Boolean) {
        remote {
            val rows = client.from(TABLE).update({ set("done", done) }) {
                select()
                filter { eq("id", id) }
            }.decodeList<TodoDto>()
            requireAffected(rows, "update")
        }
    }

    override suspend fun update(id: String, title: String, project: String?, priority: Int, notes: String?) {
        remote {
            // PostgrestUpdate has no `set(column, null)` — passing a null through the reified `set`
            // does not compile. `setToNull` emits a real JSON null, which is what lets the sheet CLEAR
            // a note or a tag; an omitted column would just leave the old server value in place.
            val rows = client.from(TABLE).update({
                set("title", title)
                set("priority", priority)
                if (project == null) setToNull("project") else set("project", project)
                if (notes == null) setToNull("notes") else set("notes", notes)
            }) {
                select()
                filter { eq("id", id) }
            }.decodeList<TodoDto>()
            requireAffected(rows, "update")
        }
    }

    override suspend fun delete(id: String) {
        // A delete that matches nothing is indistinguishable from "already gone", so we do not
        // require affected rows here. If RLS silently blocked it, the next pull brings the row back.
        remote {
            client.from(TABLE).delete { filter { eq("id", id) } }
        }
    }

    /**
     * Postgrest returns HTTP 200 with an EMPTY body when RLS filters every row — e.g. the request
     * went out with the anon key because the access token had expired. Without this guard the store
     * would mark the write SYNCED while the server never changed, and the next pull would silently
     * revert it. 403 is classified as transient, so the row stays PENDING and the drain retries it
     * once the session is refreshed.
     */
    private fun requireAffected(rows: List<TodoDto>, op: String) {
        if (rows.isEmpty()) {
            throw RemoteException(403, "$op affected no rows — RLS rejected it (expired or anon token?)")
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
