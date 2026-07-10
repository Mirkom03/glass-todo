package com.mirko.glasstodo.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import com.mirko.glasstodo.data.AuthRepository
import com.mirko.glasstodo.di.ServiceLocator
import com.mirko.glasstodo.domain.Urgency
import com.mirko.glasstodo.domain.parseInput
import com.mirko.glasstodo.ui.theme.ListoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickAddActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // rememberSaveable, not remember: this dialog Activity declares no configChanges, so a
            // rotation (or a night-mode flip) destroys and recreates it and everything typed was gone.
            // The urgency is kept as its Int priority — a plain Bundle type, no saver to argue about.
            var text by rememberSaveable { mutableStateOf("") }
            var priority by rememberSaveable { mutableStateOf(Urgency.NORMAL.priority) }
            var tags by remember { mutableStateOf(emptyList<String>()) }   // reloaded by LaunchedEffect

            LaunchedEffect(Unit) {
                tags = withContext(Dispatchers.IO) {
                    runCatching { ServiceLocator.store(applicationContext).tagSuggestions() }.getOrDefault(emptyList())
                }
            }

            // Opened from the widget, so it IS the widget's face too: same committed world as the app.
            ListoTheme { Surface {
                Column(Modifier.padding(20.dp)) {
                    Text("Nueva tarea", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        placeholder = { Text("Qué hay que hacer") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (tags.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        // One tap appends the tag you already use, instead of retyping "#hacienda-verde".
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tags.forEach { tag ->
                                AssistChip(
                                    onClick = { text = appendTag(text, tag) },
                                    label = { Text("#$tag") },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Urgencia", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Urgency.entries.forEach { level ->
                            FilterChip(
                                selected = priority == level.priority,
                                onClick = { priority = level.priority },
                                label = { Text(level.label) },
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(
                        enabled = text.isNotBlank(),
                        onClick = {
                            val parsed = parseInput(text) ?: run { finish(); return@Button }
                            val chosen = priority
                            val app = applicationContext
                            // ServiceLocator.appScope, not rememberCoroutineScope(): that scope dies
                            // with the composition, so finishing the Activity — or rotating during the
                            // save — cancelled the write halfway and the task was simply lost. Same
                            // shape as TaskDetailActivity.commit().
                            ServiceLocator.appScope.launch {
                                // Opened from the widget, so the process may be cold with a dead token.
                                runCatching { AuthRepository().ensureFreshSession() }
                                // Same store the app and the widget observe. The row lands in Room
                                // immediately; a failed push leaves it PENDING for the drain to replay.
                                runCatching { ServiceLocator.store(app).add(parsed.title, parsed.project, chosen) }
                                TodoGlanceWidget().updateAll(app)
                            }
                            finish()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Guardar") }
                }
            } }
        }
    }
}

/** Appends `#tag` unless it is already somewhere in the text. Pure so it can be unit-tested. */
internal fun appendTag(text: String, tag: String): String {
    if (Regex("(^|\\s)#${Regex.escape(tag)}(\\s|$)").containsMatchIn(text)) return text
    return if (text.isBlank()) "#$tag " else "${text.trimEnd()} #$tag "
}
