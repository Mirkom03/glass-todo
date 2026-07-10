package com.mirko.glasstodo.data.remote

/** Thin abstraction over the Supabase Postgrest calls — fakeable in unit tests. */
interface TodoRemote {
    suspend fun list(userId: String): List<TodoDto>
    suspend fun insert(dto: TodoDto)

    /** Insert-or-update by primary key. Used to replay a PENDING row whose original push failed. */
    suspend fun upsert(dto: TodoDto)

    suspend fun setDone(id: String, done: Boolean)

    /**
     * A targeted UPDATE of the fields the detail sheet owns. Deliberately NOT `upsert(dto)`: a full
     * DTO would also carry `done`, so a slow edit could clobber a toggle made on another device.
     */
    suspend fun update(id: String, title: String, project: String?, priority: Int, notes: String?)

    suspend fun delete(id: String)
}
