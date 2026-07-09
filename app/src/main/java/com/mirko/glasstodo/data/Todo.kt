package com.mirko.glasstodo.data

import kotlinx.serialization.Serializable

@Serializable
data class Todo(
    val id: String,
    val user_id: String,
    val title: String,
    val project: String? = null,
    val priority: Int = 0,
    val done: Boolean = false,
    val created_at: String? = null
)

@Serializable
data class TodoInsert(          // no id/created_at — Postgres fills them
    val user_id: String,
    val title: String,
    val project: String? = null,
    val priority: Int = 0,
    val done: Boolean = false
)
