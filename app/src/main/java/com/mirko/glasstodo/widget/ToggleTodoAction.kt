package com.mirko.glasstodo.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.ToggleableStateKey
import androidx.glance.appwidget.updateAll
import com.mirko.glasstodo.di.ServiceLocator

class ToggleTodoAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[idKey] ?: return
        // Glance injects the NEW checked value — we never recompute it from a stale render.
        val nowChecked = parameters[ToggleableStateKey] ?: return

        // Lands in Room first (the Flow re-renders both the widget and the open app), then pushes to
        // Supabase. Callbacks run in a WorkManager worker (~10 min budget), not in a ~10 s
        // BroadcastReceiver like v1's onReceive.
        runCatching { ServiceLocator.store(context).toggle(id, nowChecked) }
        TodoGlanceWidget().updateAll(context)
    }

    companion object {
        val idKey = ActionParameters.Key<String>("todo_id")
    }
}
