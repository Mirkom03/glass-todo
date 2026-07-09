package com.mirko.glasstodo.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.mirko.glasstodo.work.TodoSyncWorker

class TodoGlanceReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = TodoGlanceWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        TodoSyncWorker.schedule(context)     // safety net for when the app process is dead
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        TodoSyncWorker.cancel(context)
    }
}
