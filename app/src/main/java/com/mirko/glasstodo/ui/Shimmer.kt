package com.mirko.glasstodo.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Loading skeleton. The point: "Room has not emitted yet" must never look like "you have no tasks". */
@Composable
fun ShimmerList(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.16f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha"
    )
    Column(
        modifier.padding(top = 104.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(4) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(66.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = alpha))
            )
        }
    }
}

/** Shown only when Room really did emit an empty list — distinct from loading and from an error. */
@Composable
fun EmptyState(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Nada por hoy", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Box(Modifier.height(6.dp))
        Text(
            "Añade tu primer to-do abajo · usa #proyecto",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp
        )
    }
}
