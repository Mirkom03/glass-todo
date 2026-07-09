package com.mirko.glasstodo.data.remote

import com.mirko.glasstodo.data.local.SyncStatus
import com.mirko.glasstodo.data.local.TodoEntity
import kotlinx.serialization.Serializable

@Serializable
data class TodoDto(
    val id: String,
    val user_id: String,
    val title: String,
    val project: String? = null,
    val priority: Int = 0,
    val done: Boolean = false,
)

fun TodoDto.toEntity(status: SyncStatus) = TodoEntity(
    id = id, userId = user_id, title = title, project = project,
    priority = priority, done = done, syncStatus = status,
)

fun TodoEntity.toDto() = TodoDto(
    id = id, user_id = userId, title = title, project = project, priority = priority, done = done,
)
