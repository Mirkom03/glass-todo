package com.mirko.glasstodo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirko.glasstodo.data.TodoStore
import com.mirko.glasstodo.domain.Urgency
import com.mirko.glasstodo.domain.isPermanent
import com.mirko.glasstodo.domain.parseInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The UI is a pure function of [uiState]; every mutation goes through [TodoStore] (optimistic
 * write to Room + rollback on permanent failure), never hand-mutated composable state like v1.
 * Transient failures stay silent: the write is kept as PENDING and shows as such in the UI.
 */
class TodoViewModel(private val store: TodoStore) : ViewModel() {

    private val isSyncing = MutableStateFlow(false)
    private val errors = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TodoUiState> =
        combine(store.observeTodos(), isSyncing, errors) { todos, syncing, err ->
            TodoUiState(todos = todos, isLoading = false, isSyncing = syncing, errorMessage = err)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodoUiState(isLoading = true))

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        isSyncing.value = true
        runCatching { store.refresh() }.onFailure {
            if (it is CancellationException) throw it
            errors.value = "Sin conexión — mostrando datos locales"
        }
        isSyncing.value = false
    }

    fun add(raw: String, urgency: Urgency = Urgency.NORMAL) = viewModelScope.launch {
        val parsed = parseInput(raw) ?: return@launch
        runCatching { store.add(parsed.title, parsed.project, urgency.priority) }.onFailure {
            if (it is CancellationException) throw it
            if (it.isPermanent()) errors.value = "No se pudo guardar la tarea"
        }
    }

    fun toggle(id: String, done: Boolean) = viewModelScope.launch {
        runCatching { store.toggle(id, done) }.onFailure {
            if (it is CancellationException) throw it
            if (it.isPermanent()) errors.value = "No se pudo actualizar; cambio revertido"
        }
    }

    fun update(id: String, title: String, project: String?, priority: Int, notes: String?) =
        viewModelScope.launch {
            runCatching { store.update(id, title, project, priority, notes) }.onFailure {
                if (it is CancellationException) throw it
                if (it.isPermanent()) errors.value = "No se pudo guardar; cambios revertidos"
            }
        }

    fun delete(id: String) = viewModelScope.launch {
        runCatching { store.delete(id) }.onFailure {
            if (it is CancellationException) throw it
            if (it.isPermanent()) errors.value = "No se pudo borrar; tarea restaurada"
        }
    }

    fun errorShown() {
        errors.value = null
    }
}
