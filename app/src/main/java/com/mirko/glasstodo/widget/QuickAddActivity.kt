package com.mirko.glasstodo.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import com.mirko.glasstodo.data.AuthRepository
import com.mirko.glasstodo.di.ServiceLocator
import com.mirko.glasstodo.domain.parseInput
import kotlinx.coroutines.launch

class QuickAddActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var text by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            Surface {
                Column(Modifier.padding(20.dp)) {
                    Text("Nueva tarea", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = text.isNotBlank(),
                        onClick = {
                            val parsed = parseInput(text) ?: run { finish(); return@Button }
                            scope.launch {
                                // Opened from the widget, so the process may be cold with a dead token.
                                runCatching { AuthRepository().ensureFreshSession() }
                                // Same store the app and the widget observe. The row lands in Room
                                // immediately; a failed push leaves it PENDING for the drain to replay.
                                runCatching {
                                    ServiceLocator.store(applicationContext).add(parsed.title, parsed.project)
                                }
                                TodoGlanceWidget().updateAll(applicationContext)
                                finish()
                            }
                        }
                    ) { Text("Guardar") }
                }
            }
        }
    }
}
