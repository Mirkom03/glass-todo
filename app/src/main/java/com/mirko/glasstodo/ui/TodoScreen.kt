package com.mirko.glasstodo.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mirko.glasstodo.ui.theme.Cyan
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

@Composable
fun TodoScreen(vm: TodoViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    TodoScreenContent(
        state = state,
        onToggle = { id, done -> vm.toggle(id, done) },
        onAdd = { raw -> vm.add(raw) },
        onErrorShown = { vm.errorShown() },
    )
}

/**
 * Stateless: the screen is a pure function of [state]. Nothing is hand-mutated here — a toggle goes
 * to the ViewModel, lands in Room, and comes back through the same Flow. This is also the composable
 * the Roborazzi screenshot tests render.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreenContent(
    state: TodoUiState,
    onToggle: (String, Boolean) -> Unit,
    onAdd: (String) -> Unit,
    onErrorShown: () -> Unit = {},
) {
    val hazeState = rememberHazeState()
    val snackbar = remember { SnackbarHostState() }
    var input by rememberSaveable { mutableStateOf("") }   // survives rotation, unlike v1

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbar.showSnackbar(it)
            onErrorShown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        // SOURCE: everything the glass samples must be hazeSource AND drawn behind the effect
        AuroraBackground(Modifier.fillMaxSize().hazeSource(hazeState))

        when {
            // Three distinct states. v1 collapsed loading + empty + network-failure into one blank screen.
            state.isLoading -> ShimmerList(Modifier.fillMaxSize().hazeSource(hazeState))

            state.isEmpty -> EmptyState(Modifier.align(Alignment.Center))

            else -> LazyColumn(
                Modifier.fillMaxSize().hazeSource(hazeState),
                contentPadding = PaddingValues(top = 104.dp, start = 16.dp, end = 16.dp, bottom = 108.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.todos, key = { it.id }) { task ->       // STABLE KEY mandatory for animateItem
                    TaskRow(
                        task = task,
                        onToggle = { onToggle(task.id, !task.done) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(220),
                            placementSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            fadeOutSpec = tween(160)
                        )
                    )
                }
            }
        }

        // GLASS top bar — floats over the aurora + list source
        TopAppBar(
            title = { Text("Hoy", color = Color.White, fontWeight = FontWeight.Bold) },
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).glass(hazeState),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp))

        // GLASS add bar (bottom)
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(24.dp))
                .glass(hazeState)
                .padding(start = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Añadir un to-do…  (usa #proyecto)", color = Color.White.copy(alpha = 0.5f)) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Cyan
                ),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                if (input.isNotBlank()) {
                    onAdd(input)          // parsing the #proyecto tag lives in domain/ParseInput, not here
                    input = ""            // the row appears immediately: optimistic write to Room
                }
            }) {
                Icon(Icons.Default.Add, contentDescription = "Añadir", tint = Cyan)
            }
        }
    }
}
