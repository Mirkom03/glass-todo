package com.mirko.glasstodo.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order

class TodoRepository(
    private val c: io.github.jan.supabase.SupabaseClient = SupabaseClient.client
) {

    suspend fun signIn(email: String, password: String) =
        c.auth.signInWith(Email) { this.email = email; this.password = password }

    fun isSignedIn(): Boolean = c.auth.currentUserOrNull() != null
    private fun uid(): String = c.auth.currentUserOrNull()!!.id

    suspend fun list(): List<Todo> = c.from("todos").select {
        order("created_at", Order.DESCENDING)
    }.decodeList()

    suspend fun add(title: String, project: String? = null) {
        c.from("todos").insert(TodoInsert(user_id = uid(), title = title, project = project))
    }

    suspend fun setDone(id: String, done: Boolean) {
        c.from("todos").update({ set("done", done) }) { filter { eq("id", id) } }
    }

    suspend fun delete(id: String) {
        c.from("todos").delete { filter { eq("id", id) } }
    }
}
