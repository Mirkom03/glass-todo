package com.mirko.glasstodo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mirko.glasstodo.domain.Urgency
import com.mirko.glasstodo.ui.theme.Cyan
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

@Composable
fun TodoScreen(vm: TodoViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    TodoScreenContent(
        state = state,
        onToggle = { id, done -> vm.toggle(id, done) },
        onAdd = { raw, urgency -> vm.add(raw, urgency) },
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
    onAdd: (String, Urgency) -> Unit,
    onErrorShown: () -> Unit = {},
) {
    val hazeState = rememberHazeState()
    val snackbar = remember { SnackbarHostState() }
    var input by rememberSaveable { mutableStateOf("") }   // survives rotation, unlike v1
    // Saved as the Int, not the enum: rememberSaveable's default saver only handles Bundle-able types.
    var priority by rememberSaveable { mutableIntStateOf(Urgency.NORMAL.priority) }
    val urgency = Urgency.of(priority)

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

        // GLASS add panel (bottom). The urgency chips live INSIDE the same panel rather than floating
        // above it as separate cards, and only appear once you have typed something.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(24.dp))
                .glass(hazeState),
        ) {
            AnimatedVisibility(visible = input.isNotBlank()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Urgency.entries.forEach { level ->
                        UrgencyChip(
                            level = level,
                            selected = urgency == level,
                            onClick = { priority = level.priority },
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 6.dp, end = 4.dp),
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
                        onAdd(input, urgency)   // parsing the #proyecto tag lives in domain/ParseInput
                        input = ""              // the row appears immediately: optimistic write to Room
                        priority = Urgency.NORMAL.priority
                    }
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Añadir", tint = Cyan)
                }
            }
        }
    }
}

@Composable
private fun UrgencyChip(level: Urgency, selected: Boolean, onClick: () -> Unit) {
    val accent = urgencyColor(level)
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (selected) accent else Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            level.label,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
