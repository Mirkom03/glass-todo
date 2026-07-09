package com.mirko.glasstodo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mirko.glasstodo.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class TodoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) =
        ids.forEach { updateWidget(c, m, it) }

    override fun onEnabled(c: Context) {
        val req = PeriodicWorkRequestBuilder<TodoSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(
            "todo-widget-sync", ExistingPeriodicWorkPolicy.KEEP, req
        )
    }

    override fun onDisabled(c: Context) {
        WorkManager.getInstance(c).cancelUniqueWork("todo-widget-sync")
    }

    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)                       // keep APPWIDGET_UPDATE working
        if (i.action != ACTION_TOGGLE) return
        val itemId = i.getStringExtra(EXTRA_ID) ?: return
        val wasDone = i.getBooleanExtra(EXTRA_DONE, false)
        val pending = goAsync()                     // extend past the ~10s broadcast limit
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SupabaseTodosRest.setDone(itemId, !wasDone)
                notifyAll(c)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.mirko.glasstodo.TOGGLE"
        const val EXTRA_ID = "item_id"
        const val EXTRA_DONE = "done"

        fun notifyAll(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, TodoWidgetProvider::class.java))
            m.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        }

        fun updateWidget(c: Context, m: AppWidgetManager, id: Int) {
            val rv = RemoteViews(c.packageName, R.layout.widget_todo)

            val svc = Intent(c, TodoWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))   // UNIQUE per widget id
            }
            rv.setRemoteAdapter(R.id.widget_list, svc)
            rv.setEmptyView(R.id.widget_list, R.id.widget_empty)

            // ONE template for all rows — MUST be MUTABLE so per-row fill-in extras merge (API 31+)
            val tmpl = PendingIntent.getBroadcast(
                c, 0,
                Intent(c, TodoWidgetProvider::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            rv.setPendingIntentTemplate(R.id.widget_list, tmpl)

            // '+' opens an Activity DIRECTLY (Android 12 bans broadcast->activity trampolines). IMMUTABLE.
            val add = PendingIntent.getActivity(
                c, 1,
                Intent(c, QuickAddActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widget_add, add)

            m.updateAppWidget(id, rv)
            m.notifyAppWidgetViewDataChanged(id, R.id.widget_list)   // trigger first load
        }
    }
}
