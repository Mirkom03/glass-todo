package com.mirko.glasstodo.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState

/** Flips the widget between the task list and the grid of tags. */
class ToggleTagsAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[WidgetPrefs.SHOW_TAGS] = !(prefs[WidgetPrefs.SHOW_TAGS] ?: false)
        }
        TodoGlanceWidget().update(context, glanceId)
    }
}

/**
 * Picks a tag (or clears the filter when the tag is blank) and returns to the list.
 * Only THIS widget instance changes — `updateAppWidgetState` is scoped to [glanceId].
 */
class SelectTagAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val tag = parameters[tagKey].orEmpty()
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[WidgetPrefs.FILTER] = tag
            prefs[WidgetPrefs.SHOW_TAGS] = false
        }
        TodoGlanceWidget().update(context, glanceId)
    }

    companion object {
        val tagKey = ActionParameters.Key<String>("tag")
    }
}
