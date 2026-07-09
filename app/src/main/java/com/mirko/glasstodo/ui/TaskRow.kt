package com.mirko.glasstodo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirko.glasstodo.data.Todo
import com.mirko.glasstodo.ui.theme.Cyan

@Composable
fun TaskRow(task: Todo, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.10f))   // cheap translucent card (not per-row Haze)
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TaskCheck(task.done, onToggle)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                task.title,
                color = if (task.done) Color.White.copy(alpha = 0.5f) else Color.White,
                textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None
            )
            if (!task.project.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "#${task.project}",
                    color = Cyan.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun TaskCheck(checked: Boolean, onToggle: () -> Unit) {
    val scale by animateFloatAsState(
        if (checked) 1f else 0.9f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "scale"
    )
    val bg by animateColorAsState(
        if (checked) Cyan else Color.Transparent,
        tween(200), label = "bg"
    )
    Box(
        Modifier
            .size(26.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }   // graphicsLayer, NOT offset → no relayout
            .clip(CircleShape)
            .background(bg)
            .border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(checked, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Icon(Icons.Default.Check, null, tint = Color(0xFF041024))
        }
    }
}
