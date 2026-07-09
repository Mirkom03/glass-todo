package com.mirko.glasstodo.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.mirko.glasstodo.di.ServiceLocator

class ToggleTodoAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[idKey] ?: return

        // The new value is derived from Room at tap time, not from the (possibly stale) value that
        // was rendered into the row. Room is the source of truth for both the app and the widget.
        // The write lands locally first — the Flow re-renders everything — and then pushes to
        // Supabase. Callbacks run in a WorkManager worker (~10 min budget), not in a ~10 s
        // BroadcastReceiver like v1's onReceive.
        runCatching { ServiceLocator.store(context).toggle(id) }
        TodoGlanceWidget().updateAll(context)
    }

    companion object {
        val idKey = ActionParameters.Key<String>("todo_id")
    }
}
