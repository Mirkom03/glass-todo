package com.mirko.glasstodo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DeepNavy = Color(0xFF041024)
val Azure    = Color(0xFF1E5AFF)
val Cyan     = Color(0xFF35E0F0)

private val Scheme = darkColorScheme(
    primary = Cyan,
    secondary = Azure,
    background = DeepNavy,
    surface = DeepNavy,
    onPrimary = DeepNavy,
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun GlassTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)   // always dark; aurora is the backdrop
}
