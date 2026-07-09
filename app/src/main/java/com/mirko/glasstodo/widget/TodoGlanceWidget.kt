package com.mirko.glasstodo.widget

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import com.mirko.glasstodo.di.ServiceLocator

class TodoGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(180.dp, 110.dp), DpSize(250.dp, 110.dp), DpSize(250.dp, 250.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = ServiceLocator.store(context)
        provideContent {
            // Collected INSIDE provideContent so ANY Room write — from the app, from a widget tap,
            // from the realtime stream — re-renders the widget. The widget never touches the network:
            // v1 did a runBlocking OkHttp fetch on the binder thread, which is ANR-class.
            val todos by store.observeTodos().collectAsState(initial = emptyList())
            GlanceTheme { WidgetGlanceContent(todos) }
        }
    }
}
