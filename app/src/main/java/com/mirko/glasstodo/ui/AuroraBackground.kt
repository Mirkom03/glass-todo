package com.mirko.glasstodo.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.mirko.glasstodo.ui.theme.Azure
import com.mirko.glasstodo.ui.theme.Cyan
import com.mirko.glasstodo.ui.theme.DeepNavy

@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val resumed = rememberResumed()
    val animate = !reduced && resumed

    val phase = if (!animate) 0.35f else
        rememberInfiniteTransition("aurora").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(18_000, easing = LinearEasing), RepeatMode.Reverse
            ),
            label = "phase"
        ).value

    Canvas(modifier.fillMaxSize()) {
        drawRect(DeepNavy)

        val r1 = size.minDimension * 0.95f
        val c1 = Offset(size.width * (0.20f + 0.30f * phase), size.height * 0.22f)
        drawCircle(
            brush = Brush.radialGradient(listOf(Azure.copy(alpha = 0.55f), Color.Transparent), c1, r1),
            radius = r1, center = c1
        )

        val r2 = size.minDimension * 0.85f
        val c2 = Offset(size.width * (0.85f - 0.25f * phase), size.height * (0.70f + 0.10f * phase))
        drawCircle(
            brush = Brush.radialGradient(listOf(Cyan.copy(alpha = 0.45f), Color.Transparent), c2, r2),
            radius = r2, center = c2
        )

        val r3 = size.minDimension * 0.55f
        val c3 = Offset(size.width * (0.55f + 0.15f * phase), size.height * (0.95f - 0.10f * phase))
        drawCircle(
            brush = Brush.radialGradient(listOf(Azure.copy(alpha = 0.28f), Color.Transparent), c3, r3),
            radius = r3, center = c3
        )
    }
}
