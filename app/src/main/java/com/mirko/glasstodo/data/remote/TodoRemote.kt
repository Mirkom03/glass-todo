package com.mirko.glasstodo.data.remote

/** Thin abstraction over the Supabase Postgrest calls — fakeable in unit tests. */
interface TodoRemote {
    suspend fun list(userId: String): List<TodoDto>
    suspend fun insert(dto: TodoDto)

    /** Insert-or-update by primary key. Used to replay a PENDING row whose original push failed. */
    suspend fun upsert(dto: TodoDto)

    suspend fun setDone(id: String, done: Boolean)
    suspend fun delete(id: String)
}
