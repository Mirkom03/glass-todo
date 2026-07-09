package com.mirko.glasstodo.domain

/** The single UI/domain model shown by both the app and the widget. */
data class TodoUi(
    val id: String,
    val title: String,
    val project: String?,
    val done: Boolean,
    val pending: Boolean = false,   // true while a local write hasn't reached the server yet
    val priority: Int = 0,          // higher = more urgent; drives the sort order
)
