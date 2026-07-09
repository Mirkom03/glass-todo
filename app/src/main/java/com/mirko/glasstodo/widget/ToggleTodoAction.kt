package com.mirko.glasstodo.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.mirko.glasstodo.data.AuthRepository
import com.mirko.glasstodo.di.ServiceLocator

class ToggleTodoAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[idKey] ?: return

        // This tap may have just resurrected a dead process whose token expired hours ago. Postgrest
        // would happily fall back to the anon key and let RLS drop the write, so make sure we hold a
        // live session before touching the network.
        runCatching { AuthRepository().ensureFreshSession() }

        // The new value is derived from Room at tap time, not from the (possibly stale) value that
        // was rendered into the row. The write lands locally first — the Flow re-renders everything —
        // and then pushes to Supabase; a failed push stays PENDING and the next drain replays it.
        // Callbacks run in a WorkManager worker (~10 min budget), not in a ~10 s BroadcastReceiver.
        runCatching { ServiceLocator.store(context).toggle(id) }
        TodoGlanceWidget().updateAll(context)
    }

    companion object {
        val idKey = ActionParameters.Key<String>("todo_id")
    }
}
