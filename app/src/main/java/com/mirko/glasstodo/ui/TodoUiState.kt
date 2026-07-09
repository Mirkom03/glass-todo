package com.mirko.glasstodo.ui

import com.mirko.glasstodo.domain.TodoUi

/**
 * Immutable screen state. Loading (Room hasn't emitted yet) is distinct from empty (Room emitted
 * an empty list) — v1 collapsed both plus network-failure into one blank screen.
 */
data class TodoUiState(
    val todos: List<TodoUi> = emptyList(),
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val errorMessage: String? = null,   // transient, surfaced as a snackbar then cleared
) {
    val isEmpty: Boolean get() = !isLoading && todos.isEmpty()
}
