package com.mirko.glasstodo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * "Listo" commits to one visual world: near-black ground, one accent, type doing the work.
 *
 * The neutrals are not grey — they carry a slight blue bias toward the accent, so the ground reads
 * as chosen rather than as a default #121212. There is no light theme on purpose: a task list you
 * glance at all day is calmer dark, and a half-hearted second theme is worse than one committed one.
 */
val Ink = Color(0xFF0A0B0F)          // ground
val InkRaised = Color(0xFF12141B)    // the only elevated surface (the add dock)
val Chalk = Color(0xFFEDEFF3)        // primary text
val Chalk2 = Color(0xFF9BA3B4)       // secondary text
val Chalk3 = Color(0xFF5C6474)       // tertiary: tags, counts, rules
val Hairline = Color(0xFF1C2029)     // the only divider we ever draw

val Cyan = Color(0xFF35E0F0)         // the single accent — spent on the check and nothing else
val Amber = Color(0xFFF5A524)
val Crimson = Color(0xFFF5455C)

private val Scheme = darkColorScheme(
    primary = Cyan,
    onPrimary = Ink,
    secondary = Chalk3,
    background = Ink,
    onBackground = Chalk,
    surface = Ink,
    onSurface = Chalk,
    surfaceVariant = InkRaised,
    onSurfaceVariant = Chalk2,
    outline = Hairline,
    error = Crimson,
)

@Composable
fun ListoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
