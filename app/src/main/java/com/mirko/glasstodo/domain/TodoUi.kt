package com.mirko.glasstodo.domain

/** The single UI/domain model shown by both the app and the widget. */
data class TodoUi(
    val id: String,
    val title: String,
    val project: String?,
    val done: Boolean,
    val pending: Boolean = false,   // true while a local write hasn't reached the server yet
    val priority: Int = 0,          // higher = more urgent; drives the sort order
    // Carried here even though the widget never paints it: without the field, a notes-only change
    // emits a structurally identical list and the StateFlow never recomposes — the edit would reach
    // Room and stop there.
    val notes: String? = null,
)
