package com.mirko.glasstodo.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mirko.glasstodo.data.TodoRepository
import com.mirko.glasstodo.ui.theme.DeepNavy
import com.mirko.glasstodo.ui.theme.GlassTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val repo by lazy { TodoRepository() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlassTheme {
                var signedIn by remember { mutableStateOf(repo.isSignedIn()) }
                if (signedIn) TodoScreen(repo) else SignIn { signedIn = true }
            }
        }
    }

    @Composable
    private fun SignIn(onDone: () -> Unit) {
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
                            runCatching { repo.signIn(email.trim(), pass) }
                                .onSuccess { onDone() }
                                .onFailure { error = "No se pudo entrar. Revisa email/contraseña." }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Entrar") }
            }
        }
    }
}
