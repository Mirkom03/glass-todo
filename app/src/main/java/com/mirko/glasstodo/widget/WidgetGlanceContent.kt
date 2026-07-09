package com.mirko.glasstodo.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
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
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.mirko.glasstodo.R
import com.mirko.glasstodo.domain.TodoUi
import com.mirko.glasstodo.domain.Urgency
import com.mirko.glasstodo.ui.urgencyColor

/** The widget test matches the '+' button on this. Keep them in sync. */
const val ADD_BUTTON_DESCRIPTION = "Añadir tarea"

/** Content descriptions of the per-row state icon — the widget test matches on these. */
const val DONE_ICON_DESCRIPTION = "Hecha"
const val PENDING_ICON_DESCRIPTION = "Pendiente"

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
            .clickable(
                actionRunCallback<ToggleTodoAction>(
                    actionParametersOf(ToggleTodoAction.idKey to todo.id)
                )
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Urgency reads as a bar on the leading edge. Normal shows nothing — the absence of a
        // signal IS the signal — and a done task never shouts, whatever its urgency was.
        val urgency = Urgency.of(todo.priority)
        val showUrgency = urgency != Urgency.NORMAL && !todo.done
        if (showUrgency) {
            Spacer(
                GlanceModifier
                    .width(4.dp)
                    .height(24.dp)
                    .cornerRadius(2.dp)
                    .background(ColorProvider(urgencyColor(urgency)))
            )
            Spacer(GlanceModifier.width(8.dp))
        }

        // Deliberately NOT Glance's CheckBox. On API 31+ its translator makes the checkbox View both
        // the text view AND the action target, and a CompoundButton is clickable by default — so a
        // decorative CheckBox (onCheckedChange = null) swallows the touch across the whole row and
        // the Row's PendingIntent never fires. An ImageView and a TextView are not clickable, so the
        // tap reaches the Row. (This is exactly the bug v1.1.0 shipped with.)
        Image(
            provider = ImageProvider(if (todo.done) R.drawable.ic_check_on else R.drawable.ic_check_off),
            contentDescription = if (todo.done) DONE_ICON_DESCRIPTION else PENDING_ICON_DESCRIPTION,
            // ic_check_off is a bare white stroke — invisible on the light Material You widget
            // background — so tint it. ic_check_on is two-tone on purpose and reads on both themes.
            colorFilter = if (todo.done) null else ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
            modifier = GlanceModifier.size(22.dp),
        )
        Spacer(GlanceModifier.width(10.dp))
        Text(
            todo.title,
            maxLines = 2,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(
                color = if (todo.done) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
                fontSize = 14.sp,
                textDecoration = if (todo.done) TextDecoration.LineThrough else null,
            ),
        )
        todo.project?.takeIf { it.isNotBlank() }?.let { tag ->
            Spacer(GlanceModifier.width(6.dp))
            Text(
                "#$tag",
                maxLines = 1,
                modifier = GlanceModifier
                    .cornerRadius(8.dp)
                    .background(GlanceTheme.colors.secondaryContainer)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 11.sp),
            )
        }
    }
}
