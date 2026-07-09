package com.mirko.glasstodo.ui

import androidx.compose.ui.graphics.Color
import com.mirko.glasstodo.domain.Urgency

/** One place decides what urgency looks like, so the app and the widget can never disagree. */
fun urgencyColor(level: Urgency): Color = when (level) {
    Urgency.NORMAL -> Color(0xFF8FA3C8)     // muted: normal is the absence of a signal
    Urgency.IMPORTANT -> Color(0xFFF5A524)  // amber
    Urgency.URGENT -> Color(0xFFF5455C)     // red
}
