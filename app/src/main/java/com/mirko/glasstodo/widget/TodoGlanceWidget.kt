package com.mirko.glasstodo.widget

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import com.mirko.glasstodo.di.ServiceLocator

class TodoGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(180.dp, 110.dp), DpSize(250.dp, 110.dp), DpSize(250.dp, 250.dp))
    )

    // Default (Preferences) state definition: the tag filter and the tags/list face are stored per
    // widget instance, so two widgets can show different tags.

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = ServiceLocator.store(context)
        provideContent {
            // Collected INSIDE provideContent so ANY Room write — from the app, from a widget tap,
            // from the realtime stream — re-renders the widget. The widget never touches the network:
            // v1 did a runBlocking OkHttp fetch on the binder thread, which is ANR-class.
            val todos by store.observeTodos().collectAsState(initial = emptyList())
            val prefs = currentState<Preferences>()
            // No GlanceTheme on purpose: the content carries the app's own palette. Wrapping it in
            // the system theme is exactly how v1.3.0 came out Material-You lavender (criterio §39).
            WidgetGlanceContent(
                todos = todos,
                filter = WidgetPrefs.filterOf(prefs),
                showTags = WidgetPrefs.showTagsOf(prefs),
                // The 110dp buckets get the one-line face; anything taller can afford wrapped titles.
                compact = LocalSize.current.height < 150.dp,
            )
        }
    }
}
