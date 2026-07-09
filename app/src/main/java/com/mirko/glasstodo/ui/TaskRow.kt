package com.mirko.glasstodo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mirko.glasstodo.domain.TodoUi
import com.mirko.glasstodo.domain.Urgency
import com.mirko.glasstodo.ui.theme.Chalk
import com.mirko.glasstodo.ui.theme.Chalk2
import com.mirko.glasstodo.ui.theme.Chalk3
import com.mirko.glasstodo.ui.theme.Cyan
import com.mirko.glasstodo.ui.theme.Ink

/**
 * No card. The row sits on the ground; the whole row is the tap target, exactly like the widget.
 * Urgency is a rule on the leading edge — the edge the eye already scans — and never on a done task.
 */
@Composable
fun TaskRow(task: TodoUi, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val urgency = Urgency.of(task.priority)
    val showUrgency = urgency != Urgency.NORMAL && !task.done

    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .alpha(if (task.pending) 0.55f else 1f)     // written locally, not yet on the server
            .padding(start = 20.dp, end = 24.dp, top = 15.dp, bottom = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp)) {
            if (showUrgency) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(22.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(urgencyColor(urgency))
                )
            }
        }
        Spacer(Modifier.width(12.dp))

        TaskCheck(task.done, onToggle)
        Spacer(Modifier.width(14.dp))

        Text(
            task.title,
            modifier = Modifier.weight(1f),
            color = if (task.done) Chalk3 else Chalk,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None,
        )

        if (!task.project.isNullOrBlank()) {
            Spacer(Modifier.width(12.dp))
            // A typographic label, not a coloured chip: the accent is reserved for the check.
            Text(
                task.project.uppercase(),
                color = Chalk3,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.1.em,
            )
        }
    }
}

/** The one signature moment: the check fills with the accent and springs. */
@Composable
fun TaskCheck(checked: Boolean, onToggle: () -> Unit) {
    val scale by animateFloatAsState(
        if (checked) 1f else 0.92f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "scale"
    )
    val fill by animateColorAsState(
        if (checked) Cyan else Color.Transparent,
        tween(180), label = "fill"
    )
    val stroke by animateColorAsState(
        if (checked) Cyan else Chalk2.copy(alpha = 0.45f),
        tween(180), label = "stroke"
    )
    Box(
        Modifier
            .size(22.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }   // graphicsLayer, NOT offset → no relayout
            .clip(CircleShape)
            .background(fill)
            .border(1.5.dp, stroke, CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(checked, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Icon(Icons.Default.Check, null, tint = Ink, modifier = Modifier.size(14.dp))
        }
    }
}
