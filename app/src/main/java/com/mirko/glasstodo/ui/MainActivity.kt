package com.mirko.glasstodo.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mirko.glasstodo.data.AuthRepository
import com.mirko.glasstodo.di.ServiceLocator
import com.mirko.glasstodo.ui.theme.DeepNavy
import com.mirko.glasstodo.ui.theme.GlassTheme
import com.mirko.glasstodo.update.Updater
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val auth by lazy { AuthRepository() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlassTheme {
                // The session is the gate: Initializing is NOT "signed out" (v1 flashed the login form).
                val status by auth.sessionStatus.collectAsStateWithLifecycle()

                // enableLifecycleCallbacks=false means nothing refreshes the token on foreground but us.
                val resumed = rememberResumed()
                LaunchedEffect(resumed) {
                    if (resumed) runCatching { auth.refreshSession() }
                }

                Box(Modifier.fillMaxSize()) {
                    when (status) {
                        is SessionStatus.Authenticated -> TodoScreen(rememberTodoViewModel())
                        is SessionStatus.Initializing -> Surface(color = DeepNavy) { ShimmerList(Modifier.fillMaxSize()) }
                        else -> SignIn(auth)   // NotAuthenticated | RefreshFailure
                    }
                    UpdateGate()
                }
            }
        }
    }
}

/** The ViewModel is built on the ONE TodoStore the widget also writes to. */
@Composable
private fun rememberTodoViewModel(): TodoViewModel {
    val ctx = LocalContext.current.applicationContext
    val factory = remember(ctx) {
        viewModelFactory { initializer { TodoViewModel(ServiceLocator.store(ctx)) } }
    }
    return viewModel(factory = factory)
}

@Composable
private fun SignIn(auth: AuthRepository) {
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Surface(color = DeepNavy) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Glass Todo", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text("Inicia sesión una vez", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                email, { email = it },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                pass, { pass = it },
                label = { Text("Contraseña") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    error = null
                    scope.launch {
                        // No onDone callback: signIn flips sessionStatus and the gate above re-composes.
                        runCatching { auth.signIn(email.trim(), pass) }
                            .onFailure { error = "No se pudo entrar. Revisa email/contraseña." }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Entrar") }
        }
    }
}

/** Checks GitHub for a newer release on launch; offers a 1-tap update. */
@Composable
private fun UpdateGate() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<Updater.Update?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        update = withContext(Dispatchers.IO) { runCatching { Updater.check() }.getOrNull() }
    }

    val u = update
    if (u != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) update = null },
            title = { Text("Nueva versión ${u.version}") },
            text = { Text(if (busy) "Descargando…" else "Hay una actualización disponible.") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { Updater.downloadAndInstall(ctx, u.apkUrl) } }
                        busy = false
                        update = null
                    }
                }) { Text("Actualizar") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { update = null }) { Text("Ahora no") }
            }
        )
    }
}
