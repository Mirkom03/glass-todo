package com.mirko.glasstodo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.HazeMaterials

/** Apply to a floating surface that should blur the [state] source drawn BEHIND it. */
@Composable
fun Modifier.glass(state: HazeState): Modifier = this.hazeEffect(
    state = state,
    style = HazeMaterials.thin(MaterialTheme.colorScheme.surface)
) {
    blurRadius = 24.dp
    noiseFactor = 0.05f
    tints = listOf(HazeTint(Color(0x3327E0F0)))   // azure→cyan wash
}
