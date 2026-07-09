package com.mirko.glasstodo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mirko.glasstodo.domain.TodoUi
import com.mirko.glasstodo.domain.Urgency
import com.mirko.glasstodo.ui.theme.Chalk
import com.mirko.glasstodo.ui.theme.Chalk2
import com.mirko.glasstodo.ui.theme.Chalk3
import com.mirko.glasstodo.ui.theme.Cyan
import com.mirko.glasstodo.ui.theme.Hairline
import com.mirko.glasstodo.ui.theme.Ink
import com.mirko.glasstodo.ui.theme.InkRaised

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
 * the Roborazzi screenshots render.
 *
 * Structure: the header is CONTENT, not chrome. No cards; rows sit on the ground, divided by one
 * hairline. The accent is spent on the check and on the progress rule — nowhere else.
 */
@Composable
fun TodoScreenContent(
    state: TodoUiState,
    onToggle: (String, Boolean) -> Unit,
    onAdd: (String, Urgency) -> Unit,
    onErrorShown: () -> Unit = {},
) {
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

    val pending = state.todos.filter { !it.done }
    val done = state.todos.filter { it.done }

    Box(Modifier.fillMaxSize().background(Ink)) {
        when {
            // Three distinct states. v1 collapsed loading + empty + network-failure into one blank screen.
            state.isLoading -> ShimmerList(Modifier.fillMaxSize())

            state.isEmpty -> EmptyState(Modifier.align(Alignment.Center))

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 132.dp),
            ) {
                item(key = "header") {
                    Header(pending = pending.size, done = done.size)
                }
                items(pending, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        onToggle = { onToggle(task.id, true) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(200),
                            placementSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                            fadeOutSpec = tween(160),
                        ),
                    )
                }
                if (done.isNotEmpty()) {
                    item(key = "done-divider") { SectionRule("Hechas", done.size) }
                    items(done, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            onToggle = { onToggle(task.id, false) },
                            modifier = Modifier.animateItem(
                                placementSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            ),
                        )
                    }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp))

        AddDock(
            input = input,
            onInput = { input = it },
            urgency = urgency,
            onUrgency = { priority = it.priority },
            onSubmit = {
                if (input.isNotBlank()) {
                    onAdd(input, urgency)     // parsing the #proyecto tag lives in domain/ParseInput
                    input = ""                // the row appears immediately: optimistic write to Room
                    priority = Urgency.NORMAL.priority
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * The header IS the summary. When nothing is pending the number is replaced by the app's own word —
 * that payoff is the point of calling it Listo.
 */
@Composable
private fun Header(pending: Int, done: Int) {
    val total = pending + done
    val target = if (total == 0) 0f else done.toFloat() / total
    val progress by animateFloatAsState(target, spring(stiffness = Spring.StiffnessLow), label = "progress")

    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 44.dp, bottom = 22.dp)) {
        AnimatedContent(
            targetState = pending,
            transitionSpec = { (fadeIn(tween(220))).togetherWith(fadeOut(tween(140))) },
            label = "count",
        ) { count ->
            if (count == 0) {
                Text(
                    "Listo.",
                    color = Cyan,
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = (-0.03).em,
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "$count",
                        color = Chalk,
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = (-0.04).em,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (count == 1) "pendiente" else "pendientes",
                        color = Chalk2,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // The only other place the accent is allowed: a hairline that fills as the day resolves.
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(Hairline)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Cyan)
            )
        }
    }
}

@Composable
private fun SectionRule(label: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 30.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            color = Chalk3,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.14.em,
        )
        Spacer(Modifier.width(8.dp))
        Text("$count", color = Chalk3, fontSize = 10.sp, letterSpacing = 0.14.em)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.height(1.dp).fillMaxWidth().background(Hairline))
    }
}

@Composable
private fun AddDock(
    input: String,
    onInput: (String) -> Unit,
    urgency: Urgency,
    onUrgency: (Urgency) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(InkRaised)) {
        // A single hairline separates the dock from the list. No card, no blur, no shadow.
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
        AnimatedVisibility(visible = input.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Urgency.entries.forEach { level ->
                    UrgencyChip(level = level, selected = urgency == level, onClick = { onUrgency(level) })
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = input,
                onValueChange = onInput,
                placeholder = { Text("Añadir…  usa #etiqueta", color = Chalk3, fontSize = 15.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Chalk,
                    unfocusedTextColor = Chalk,
                    cursorColor = Cyan,
                ),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSubmit, enabled = input.isNotBlank()) {
                Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = "Añadir",
                    tint = if (input.isNotBlank()) Cyan else Chalk3,
                )
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
            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (selected) accent else Hairline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            level.label,
            color = if (selected) Chalk else Chalk3,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Nada que hacer", color = Chalk, fontSize = 26.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(8.dp))
        Text("Escribe abajo. Usa #etiqueta.", color = Chalk3, fontSize = 14.sp)
    }
}

