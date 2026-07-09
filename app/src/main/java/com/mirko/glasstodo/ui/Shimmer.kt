package com.mirko.glasstodo.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mirko.glasstodo.ui.theme.Chalk3

/**
 * Loading skeleton. The point: "Room has not emitted yet" must never look like "you have no tasks".
 * It mirrors the real layout — one big header block, then rows — so nothing jumps when data lands.
 */
@Composable
fun ShimmerList(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.13f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha"
    )
    Column(modifier.padding(start = 24.dp, end = 24.dp, top = 44.dp)) {
        Bar(width = 0.34f, height = 46.dp, alpha = alpha)
        Box(Modifier.height(24.dp))
        Bar(width = 1f, height = 2.dp, alpha = alpha)
        Box(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
            repeat(5) { Bar(width = listOf(0.86f, 0.7f, 0.78f, 0.62f, 0.74f)[it], height = 16.dp, alpha = alpha) }
        }
    }
}

@Composable
private fun Bar(width: Float, height: androidx.compose.ui.unit.Dp, alpha: Float) {
    Box(
        Modifier
            .fillMaxWidth(width)
            .height(height)
            .clip(RoundedCornerShape(3.dp))
            .background(Chalk3.copy(alpha = alpha))
    )
}
