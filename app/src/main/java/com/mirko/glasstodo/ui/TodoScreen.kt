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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirko.glasstodo.data.Todo
import com.mirko.glasstodo.data.TodoRepository
import com.mirko.glasstodo.ui.theme.Cyan
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(repo: TodoRepository) {
    val hazeState = rememberHazeState()
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<Todo>>(emptyList()) }
    var input by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { tasks = runCatching { repo.list() }.getOrDefault(emptyList()) }

    Box(Modifier.fillMaxSize()) {
        // SOURCE: everything the glass samples must be hazeSource AND drawn behind the effect
        AuroraBackground(Modifier.fillMaxSize().hazeSource(hazeState))

        LazyColumn(
            Modifier.fillMaxSize().hazeSource(hazeState),
            contentPadding = PaddingValues(top = 104.dp, start = 16.dp, end = 16.dp, bottom = 108.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(tasks, key = { it.id }) { task ->               // STABLE KEY mandatory for animateItem
                TaskRow(
                    task = task,
                    onToggle = {
                        val newDone = !task.done
                        tasks = tasks.map { if (it.id == task.id) it.copy(done = newDone) else it }
                        scope.launch { runCatching { repo.setDone(task.id, newDone) } }
                    },
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

        // GLASS top bar — floats over the aurora + list source
        TopAppBar(
            title = { Text("Hoy", color = Color.White, fontWeight = FontWeight.Bold) },
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).glass(hazeState),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )

        // GLASS add bar (bottom) — matches the mockup
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
                val raw = input.trim()
                if (raw.isNotBlank()) {
                    val proj = Regex("#(\\S+)").find(raw)?.groupValues?.get(1)
                    val title = raw.replace(Regex("#\\S+"), "").trim().ifBlank { raw }
                    input = ""
                    scope.launch {
                        runCatching {
                            repo.add(title, proj)
                            tasks = repo.list()
                        }
                    }
                }
            }) {
                Icon(Icons.Default.Add, contentDescription = "Añadir", tint = Cyan)
            }
        }
    }
}
