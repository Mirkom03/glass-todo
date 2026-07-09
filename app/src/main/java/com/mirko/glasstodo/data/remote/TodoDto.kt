package com.mirko.glasstodo.data.remote

import com.mirko.glasstodo.data.local.SyncStatus
import com.mirko.glasstodo.data.local.TodoEntity
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime

@Serializable
data class TodoDto(
    val id: String,
    val user_id: String,
    val title: String,
    val project: String? = null,
    val priority: Int = 0,
    val done: Boolean = false,
    val created_at: String? = null,   // timestamptz ISO-8601; sent on insert so offline creations keep their real time
)

/** Postgres returns "2026-07-09T05:38:00.123456+00:00" (or "...Z"); both parse as OffsetDateTime. */
fun parseTimestampMillis(iso: String?): Long? = iso?.let {
    runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
}

fun TodoDto.toEntity(status: SyncStatus) = TodoEntity(
    id = id, userId = user_id, title = title, project = project,
    priority = priority, done = done, syncStatus = status,
    createdAt = parseTimestampMillis(created_at) ?: System.currentTimeMillis(),
)

fun TodoEntity.toDto() = TodoDto(
    id = id, user_id = userId, title = title, project = project, priority = priority, done = done,
    created_at = Instant.ofEpochMilli(createdAt).toString(),
)
