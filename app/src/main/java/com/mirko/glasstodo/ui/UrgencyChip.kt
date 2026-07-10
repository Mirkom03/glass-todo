package com.mirko.glasstodo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirko.glasstodo.domain.Urgency
import com.mirko.glasstodo.ui.theme.Chalk
import com.mirko.glasstodo.ui.theme.Chalk3
import com.mirko.glasstodo.ui.theme.Hairline

/** Shared by the add dock and the detail sheet: the same three levels must look the same in both. */
@Composable
internal fun UrgencyChip(level: Urgency, selected: Boolean, onClick: () -> Unit) {
    val accent = urgencyColor(level)
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (selected) accent else Hairline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            level.label,
            color = if (selected) Chalk else Chalk3,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
