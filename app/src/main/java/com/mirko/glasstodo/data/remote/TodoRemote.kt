package com.mirko.glasstodo.data.remote

/** Thin abstraction over the Supabase Postgrest calls — fakeable in unit tests. */
interface TodoRemote {
    suspend fun list(userId: String): List<TodoDto>
    suspend fun insert(dto: TodoDto)
    suspend fun setDone(id: String, done: Boolean)
    suspend fun delete(id: String)
}
