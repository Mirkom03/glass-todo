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
        val done = parameters[doneKey] ?: return

        // This tap may have just resurrected a dead process whose token expired hours ago. Postgrest
        // would happily fall back to the anon key and let RLS drop the write, so make sure we hold a
        // live session before touching the network.
        runCatching { AuthRepository().ensureFreshSession() }

        // `done` is the user's INTENT: the negation of the state the tapped row was SHOWING. It used
        // to be derived from Room at tap time ("safer than a stale render") — field-proven wrong on
        // 2026-07-09: when Room moves under a stale render, flip-whatever INVERTS the user's action.
        // The write lands locally first — the Flow re-renders everything — then pushes to Supabase;
        // a failed push stays PENDING and the next drain replays it. Callbacks run in a WorkManager
        // worker (~10 min budget), not in a ~10 s BroadcastReceiver.
        runCatching { ServiceLocator.store(context).toggle(id, done) }
        TodoGlanceWidget().updateAll(context)
    }

    companion object {
        val idKey = ActionParameters.Key<String>("todo_id")
        val doneKey = ActionParameters.Key<Boolean>("todo_done")
    }
}
