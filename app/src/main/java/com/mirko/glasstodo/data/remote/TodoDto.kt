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
    val notes: String? = null,        // the detail sheet's description
    val created_at: String? = null,   // timestamptz ISO-8601; sent on insert so offline creations keep their real time
)

/**
 * PostgREST's JSON encoder returns "2026-07-09T05:38:00.123456+00:00" (or "...Z"), which
 * `OffsetDateTime.parse` eats directly. Realtime does NOT go through that encoder: a
 * `postgres_changes` payload carries the column's native text — "2026-07-09 05:38:00.123456+00",
 * with a space instead of the `T` and a two-digit offset. Both forms used to throw, so this returned
 * null and `toEntity` stamped `createdAt = System.currentTimeMillis()`: every realtime snapshot
 * reshuffled the list (`ORDER BY createdAt DESC`) and then fed that invented time back to the server
 * on the next drain, which does send `created_at`.
 *
 * The offset regex is anchored behind the time so a bare date ("2026-07-09") is not mistaken for one.
 */
private val SHORT_OFFSET = Regex("(T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)([+-]\\d{2})$")

fun parseTimestampMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    val normalized = iso.trim().replaceFirst(" ", "T").replace(SHORT_OFFSET, "$1$2:00")
    return runCatching { OffsetDateTime.parse(normalized).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(normalized).toEpochMilli() }.getOrNull()
}

fun TodoDto.toEntity(status: SyncStatus) = TodoEntity(
    id = id, userId = user_id, title = title, project = project,
    priority = priority, done = done, notes = notes, syncStatus = status,
    createdAt = parseTimestampMillis(created_at) ?: System.currentTimeMillis(),
)

fun TodoEntity.toDto() = TodoDto(
    id = id, user_id = userId, title = title, project = project, priority = priority, done = done,
    notes = notes,
    created_at = Instant.ofEpochMilli(createdAt).toString(),
)
