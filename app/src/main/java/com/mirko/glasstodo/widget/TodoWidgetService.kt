package com.mirko.glasstodo.widget

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.mirko.glasstodo.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class TodoWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(i: Intent) = TodoFactory(applicationContext)
}

class TodoFactory(private val c: Context) : RemoteViewsService.RemoteViewsFactory {
    private var items: List<WidgetTodo> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {                 // binder thread, ~20s budget → sync network OK
        items = runBlocking(Dispatchers.IO) {
            runCatching { SupabaseTodosRest.fetch() }.getOrDefault(emptyList())
        }
    }

    override fun getCount() = items.size

    override fun getViewAt(pos: Int): RemoteViews {
        val t = items[pos]
        return RemoteViews(c.packageName, R.layout.widget_todo_item).apply {
            setTextViewText(R.id.item_text, t.title)
            setImageViewResource(
                R.id.item_check,
                if (t.done) R.drawable.ic_check_on else R.drawable.ic_check_off
            )
            setInt(
                R.id.item_text, "setPaintFlags",
                if (t.done) Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG
                else Paint.ANTI_ALIAS_FLAG
            )
            if (!t.project.isNullOrBlank()) {
                setTextViewText(R.id.item_project, "#${t.project}")
                setViewVisibility(R.id.item_project, View.VISIBLE)
            } else {
                setViewVisibility(R.id.item_project, View.GONE)
            }
            // WHOLE ROW is the tap target → toggles done (merges into the broadcast template)
            val fill = Intent().apply {
                putExtra(TodoWidgetProvider.EXTRA_ID, t.id)
                putExtra(TodoWidgetProvider.EXTRA_DONE, t.done)
            }
            setOnClickFillInIntent(R.id.item_root, fill)
        }
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 1
    override fun getItemId(pos: Int) = items[pos].id.hashCode().toLong()
    override fun hasStableIds() = true
    override fun onDestroy() {}
}
