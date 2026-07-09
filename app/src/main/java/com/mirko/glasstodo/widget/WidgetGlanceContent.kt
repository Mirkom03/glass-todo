package com.mirko.glasstodo.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.GridCells
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.LazyVerticalGrid
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

/** The widget tests match on these. Keep them in sync. */
const val ADD_BUTTON_DESCRIPTION = "Añadir tarea"
const val TAGS_BUTTON_DESCRIPTION = "Ver etiquetas"
const val DONE_ICON_DESCRIPTION = "Hecha"
const val PENDING_ICON_DESCRIPTION = "Pendiente"
const val ALL_TAGS_LABEL = "Todas"

/**
 * The ONE composable the real widget and the unit tests both render — that is what makes the widget
 * testable at all.
 *
 * Two faces, one widget. The list never gives up height to a filter bar; the `#` button flips to a
 * grid of tags with their pending counts, and picking one flips back to a filtered list. Height is
 * the scarce resource on a home screen, so the filter lives on the other side of the card.
 *
 * RemoteViews cannot blur, so there is no glass here either: flat surface, one accent, type.
 */
@Composable
fun WidgetGlanceContent(
    todos: List<TodoUi>,
    filter: String? = null,
    showTags: Boolean = false,
    /**
     * Only the CONTAINER changes, never a row. Glance's LazyColumn/LazyVerticalGrid become RemoteViews
     * collection adapters, and an adapter is not populated when you inflate RemoteViews outside an
     * AppWidgetHost — so the screenshot harness would render an empty widget. Screenshots pass
     * `lazy = false` to get a plain Column; production always scrolls.
     */
    lazy: Boolean = true,
) {
    val visible = todos.filter { filter == null || it.project == filter }

    Column(
        GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Header(filter = filter, showTags = showTags)
        Spacer(GlanceModifier.height(10.dp))

        when {
            showTags -> TagGrid(todos, lazy)
            visible.isEmpty() -> Text(
                if (filter == null) "Nada pendiente" else "Nada en #$filter",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
            )
            // Once you have filtered, every row carries the same tag — repeating it is noise, and it
            // steals the width that makes titles wrap.
            lazy -> LazyColumn {
                items(visible, itemId = { it.id.hashCode().toLong() }) { todo ->
                    TodoRow(todo, showTag = filter == null)
                }
            }
            else -> Column { visible.forEach { TodoRow(it, showTag = filter == null) } }
        }
    }
}

@Composable
private fun Header(filter: String?, showTags: Boolean) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        when {
            showTags -> Title("Etiquetas")
            filter != null -> {
                // The filter pill IS the way back: tapping it shows everything again.
                Text(
                    "#$filter",
                    maxLines = 1,
                    modifier = GlanceModifier
                        .cornerRadius(9.dp)
                        .background(GlanceTheme.colors.secondaryContainer)
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                        .clickable(actionRunCallback<SelectTagAction>(
                            actionParametersOf(SelectTagAction.tagKey to "")
                        )),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSecondaryContainer,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            else -> Title("Listo")
        }
        Spacer(GlanceModifier.defaultWeight())
        CircleButton(
            label = "#",
            description = TAGS_BUTTON_DESCRIPTION,
            highlighted = showTags,
            action = actionRunCallback<ToggleTagsAction>(),
        )
        Spacer(GlanceModifier.width(6.dp))
        AddButton()
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
}

@Composable
private fun CircleButton(label: String, description: String, highlighted: Boolean, action: Action) {
    // Glance has no content description on Text, so the tests match this button by its label.
    Text(
        label,
        modifier = GlanceModifier
            .size(30.dp)
            .cornerRadius(15.dp)
            .background(
                if (highlighted) GlanceTheme.colors.primary else GlanceTheme.colors.secondaryContainer
            )
            .padding(top = 5.dp)
            .clickable(action),
        style = TextStyle(
            color = if (highlighted) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSecondaryContainer,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.glance.text.TextAlign.Center,
        ),
    )
}

@Composable
private fun AddButton() {
    Image(
        provider = ImageProvider(R.drawable.ic_add),
        contentDescription = ADD_BUTTON_DESCRIPTION,
        // ic_add is hardcoded white; untinted it was invisible on the light Material You surface.
        colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
        modifier = GlanceModifier
            .size(30.dp)
            .cornerRadius(15.dp)
            .background(GlanceTheme.colors.primaryContainer)
            .padding(7.dp)
            // Opens an Activity directly: Android 12 bans broadcast -> activity trampolines.
            .clickable(actionStartActivity<QuickAddActivity>()),
    )
}

@Composable
private fun TagGrid(todos: List<TodoUi>, lazy: Boolean) {
    val pending = todos.filter { !it.done }
    // Counts are of PENDING work: a tag whose tasks are all done should not shout for attention.
    val tags = pending.mapNotNull { it.project?.takeIf(String::isNotBlank) }
        .groupingBy { it }.eachCount()
        .toList().sortedByDescending { it.second }

    val tiles = listOf(ALL_TAGS_LABEL to pending.size) + tags

    if (lazy) {
        LazyVerticalGrid(gridCells = GridCells.Fixed(2)) {
            items(tiles, itemId = { it.first.hashCode().toLong() }) { (name, count) ->
                TagTile(name = name, count = count, all = name == ALL_TAGS_LABEL)
            }
        }
    } else {
        Column {
            tiles.chunked(2).forEach { pair ->
                Row(GlanceModifier.fillMaxWidth()) {
                    pair.forEach { (name, count) ->
                        Column(GlanceModifier.defaultWeight()) {
                            TagTile(name = name, count = count, all = name == ALL_TAGS_LABEL)
                        }
                    }
                    if (pair.size == 1) Spacer(GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun TagTile(name: String, count: Int, all: Boolean) {
    Column(
        GlanceModifier
            .fillMaxWidth()
            .padding(2.dp)
            .cornerRadius(12.dp)
            .background(if (all) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clickable(
                actionRunCallback<SelectTagAction>(
                    actionParametersOf(SelectTagAction.tagKey to if (all) "" else name)
                )
            )
    ) {
        Text(
            if (all) name else "#$name",
            maxLines = 1,
            style = TextStyle(
                color = if (all) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSecondaryContainer,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Text(
            "$count",
            style = TextStyle(
                color = if (all) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSecondaryContainer,
                fontSize = 11.sp,
            ),
        )
    }
}

@Composable
private fun TodoRow(todo: TodoUi, showTag: Boolean = true) {
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
        // Urgency reads as a rule on the leading edge. Normal shows nothing — the absence of a
        // signal IS the signal — and a done task never shouts, whatever its urgency was.
        val urgency = Urgency.of(todo.priority)
        if (urgency != Urgency.NORMAL && !todo.done) {
            Spacer(
                GlanceModifier
                    .width(3.dp)
                    .height(22.dp)
                    .cornerRadius(2.dp)
                    .background(ColorProvider(urgencyColor(urgency)))
            )
            Spacer(GlanceModifier.width(8.dp))
        } else {
            Spacer(GlanceModifier.width(11.dp))
        }

        // Deliberately NOT Glance's CheckBox. On API 31+ its translator makes the checkbox View both
        // the text view AND the action target, and a CompoundButton is clickable by default — so a
        // decorative CheckBox (onCheckedChange = null) swallows the touch across the whole row and
        // the Row's PendingIntent never fires. An ImageView and a TextView are not clickable, so the
        // tap reaches the Row. (This is exactly the bug v1.1.0 shipped with.)
        Image(
            provider = ImageProvider(if (todo.done) R.drawable.ic_check_on else R.drawable.ic_check_off),
            contentDescription = if (todo.done) DONE_ICON_DESCRIPTION else PENDING_ICON_DESCRIPTION,
            colorFilter = if (todo.done) null else ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
            modifier = GlanceModifier.size(20.dp),
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
        if (showTag) {
            todo.project?.takeIf { it.isNotBlank() }?.let { tag ->
                Spacer(GlanceModifier.width(8.dp))
                // A typographic label, not a coloured chip: the accent belongs to the check.
                Text(
                    tag.uppercase(),
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 9.sp),
                )
            }
        }
    }
}
