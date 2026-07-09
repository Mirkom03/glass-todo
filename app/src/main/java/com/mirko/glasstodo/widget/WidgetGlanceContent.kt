package com.mirko.glasstodo.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import com.mirko.glasstodo.R
import com.mirko.glasstodo.domain.TodoUi

/** The widget test matches the '+' button on this. Keep them in sync. */
const val ADD_BUTTON_DESCRIPTION = "Añadir tarea"

/**
 * The ONE composable the real widget and the unit tests both render — that is what makes the widget
 * testable at all. RemoteViews/Glance cannot blur, so there is no liquid glass here: a flat rounded
 * surface on the Material You palette is the honest result.
 */
@Composable
fun WidgetGlanceContent(todos: List<TodoUi>) {
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(14.dp)
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Tareas",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(GlanceModifier.defaultWeight())
            Image(
                provider = ImageProvider(R.drawable.ic_add),
                contentDescription = ADD_BUTTON_DESCRIPTION,
                modifier = GlanceModifier
                    .size(38.dp)
                    .cornerRadius(19.dp)
                    .background(GlanceTheme.colors.primaryContainer)
                    .padding(9.dp)
                    // Opens an Activity directly: Android 12 bans broadcast -> activity trampolines.
                    .clickable(actionStartActivity<QuickAddActivity>()),
            )
        }
        Spacer(GlanceModifier.height(10.dp))

        if (todos.isEmpty()) {
            Text(
                "Sin tareas · toca + para añadir",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
            )
        } else {
            LazyColumn {
                items(todos, itemId = { it.id.hashCode().toLong() }) { todo -> TodoRow(todo) }
            }
        }
    }
}

@Composable
private fun TodoRow(todo: TodoUi) {
    Row(
        GlanceModifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 6.dp)
            // THE tap fix. v1 gave every row the SAME mutable PendingIntent template and relied on
            // per-row fill-in extras merging into it; launchers that drop the merge left
            // `getStringExtra(EXTRA_ID) ?: return` and the checkbox did nothing. Glance registers one
            // typed action per row — no template, nothing to merge — and the whole row is the target.
            //
            // The action lives on the Row, not on the CheckBox, for two reasons: Glance wraps a
            // checkbox's action in an internal CompoundButtonAction that no public test API can
            // inspect (so the wiring would be unverifiable), and a full-width row is a far better
            // tap target on a home screen.
            .clickable(
                actionRunCallback<ToggleTodoAction>(
                    actionParametersOf(ToggleTodoAction.idKey to todo.id)
                )
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBox(
            checked = todo.done,
            onCheckedChange = null,          // visual only; the Row owns the click
            text = todo.title + (todo.project?.let { "  #$it" } ?: ""),
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 14.sp,
                textDecoration = if (todo.done) TextDecoration.LineThrough else null,
            ),
        )
    }
}
