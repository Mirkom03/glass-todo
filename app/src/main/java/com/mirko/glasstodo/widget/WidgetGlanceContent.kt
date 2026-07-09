package com.mirko.glasstodo.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.LinearProgressIndicator
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
import com.mirko.glasstodo.ui.theme.Chalk
import com.mirko.glasstodo.ui.theme.Chalk2
import com.mirko.glasstodo.ui.theme.Chalk3
import com.mirko.glasstodo.ui.theme.Cyan
import com.mirko.glasstodo.ui.theme.Hairline
import com.mirko.glasstodo.ui.theme.Ink
import com.mirko.glasstodo.ui.urgencyColor

/** The widget tests match on these. Keep them in sync. */
const val ADD_BUTTON_DESCRIPTION = "Añadir tarea"
const val TAGS_BUTTON_DESCRIPTION = "Ver etiquetas"
const val DONE_ICON_DESCRIPTION = "Hecha"
const val PENDING_ICON_DESCRIPTION = "Pendiente"
const val ALL_TAGS_LABEL = "Todas"

/*
 * The widget wears the app's OWN palette (ui/theme/Theme.kt), imported as fixed providers — exactly
 * the way TaskRow imports Chalk and Cyan instead of asking MaterialTheme. It reads NOTHING from
 * GlanceTheme: consulting the system theme is how v1.3.0 came out lavender while the app was Ink
 * («parecen totalmente diferentes», criterio §39). One committed world means the launcher's dynamic
 * colors are ignored on purpose, in light and in dark alike.
 */
private val ink = ColorProvider(Ink)
private val chalk = ColorProvider(Chalk)
private val chalk2 = ColorProvider(Chalk2)
private val chalk3 = ColorProvider(Chalk3)
private val cyan = ColorProvider(Cyan)
private val hairline = ColorProvider(Hairline)

/**
 * The ONE composable the real widget and the unit tests both render — that is what makes the widget
 * testable at all.
 *
 * Two faces, one widget. The list never gives up height to a filter bar; the `#` button flips to a
 * grid of tags with their pending counts, and picking one flips back to a filtered list. Height is
 * the scarce resource on a home screen, so the filter lives on the other side of the card.
 *
 * RemoteViews cannot blur, so there is no glass here either: flat Ink, one accent, type.
 */
@Composable
fun WidgetGlanceContent(
    todos: List<TodoUi>,
    filter: String? = null,
    showTags: Boolean = false,
    /**
     * The 110dp-tall placements. One wrapped title there eats the only row that fits, so the compact
     * face gives every task exactly one full-width line and drops the tag label.
     */
    compact: Boolean = false,
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
            .background(ink)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Header(todos = todos, visible = visible, filter = filter, showTags = showTags)
        Spacer(GlanceModifier.height(8.dp))
        // The rule tracks what the face shows: the whole list, or the filtered slice.
        ProgressRule(scope = if (showTags) todos else visible)
        Spacer(GlanceModifier.height(10.dp))

        val showTag = filter == null && !compact
        when {
            showTags -> TagGrid(todos, filter, lazy)
            visible.isEmpty() -> Text(
                // The app's own words for the same state.
                if (filter == null) "Nada que hacer" else "Nada en #$filter",
                style = TextStyle(color = chalk3, fontSize = 13.sp),
            )
            // Once you have filtered, every row carries the same tag — repeating it is noise, and it
            // steals the width that makes titles wrap.
            lazy -> LazyColumn {
                items(visible, itemId = { it.id.hashCode().toLong() }) { todo ->
                    TodoRow(todo, showTag = showTag, compact = compact)
                }
            }
            else -> Column { visible.forEach { TodoRow(it, showTag = showTag, compact = compact) } }
        }
    }
}

/**
 * The header IS the summary, same as the app: the count of pending work — and when nothing is
 * pending, the app's own word. «Listo.» on the home screen is the whole payoff of the name.
 */
@Composable
private fun Header(todos: List<TodoUi>, visible: List<TodoUi>, filter: String?, showTags: Boolean) {
    val pending = todos.count { !it.done }
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        when {
            // Faces are named in the app's section-rule voice (HECHAS), not with a bold title.
            showTags -> Text(
                "ETIQUETAS",
                style = TextStyle(color = chalk3, fontSize = 11.sp, fontWeight = FontWeight.Medium),
            )
            filter != null -> {
                // The filter pill IS the way back: tapping it shows everything again.
                Text(
                    "#$filter",
                    maxLines = 1,
                    modifier = GlanceModifier
                        .background(ImageProvider(R.drawable.widget_pill))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .clickable(actionRunCallback<SelectTagAction>(
                            actionParametersOf(SelectTagAction.tagKey to "")
                        )),
                    style = TextStyle(color = chalk, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                )
                // The count keeps this face anchored in the same hierarchy as the list face.
                val open = visible.count { !it.done }
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    if (open == 1) "1 pendiente" else "$open pendientes",
                    maxLines = 1,
                    style = TextStyle(color = chalk2, fontSize = 12.sp),
                )
            }
            pending == 0 -> Text("Listo.", style = TextStyle(color = cyan, fontSize = 26.sp))
            else -> Row(verticalAlignment = Alignment.Bottom) {
                // Glance has no Light weight, so the numeral leans on size alone — same idea, at scale.
                Text("$pending", style = TextStyle(color = chalk, fontSize = 26.sp))
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    if (pending == 1) "pendiente" else "pendientes",
                    modifier = GlanceModifier.padding(bottom = 4.dp),
                    style = TextStyle(color = chalk2, fontSize = 12.sp),
                )
            }
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

/** The app's signature: the 2dp rule that fills with the accent as the day resolves. */
@Composable
private fun ProgressRule(scope: List<TodoUi>) {
    val done = scope.count { it.done }
    LinearProgressIndicator(
        progress = if (scope.isEmpty()) 0f else done / scope.size.toFloat(),
        modifier = GlanceModifier.fillMaxWidth().height(2.dp),
        color = cyan,
        backgroundColor = hairline,
    )
}

@Composable
private fun CircleButton(label: String, description: String, highlighted: Boolean, action: Action) {
    // Glance has no content description on Text, so the tests match this button by its label.
    // The active face earns the accent — the same way the check earns it when it fills.
    Text(
        label,
        modifier = GlanceModifier
            .size(30.dp)
            .cornerRadius(15.dp)
            .background(ImageProvider(
                if (highlighted) R.drawable.widget_circle_active else R.drawable.widget_circle
            ))
            .padding(top = 5.dp)
            .clickable(action),
        style = TextStyle(
            color = if (highlighted) cyan else chalk2,
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
        // Cyan on the quiet raised circle — the same accent the app spends on its send arrow.
        colorFilter = ColorFilter.tint(cyan),
        modifier = GlanceModifier
            .size(30.dp)
            .cornerRadius(15.dp)
            .background(ImageProvider(R.drawable.widget_circle))
            .padding(7.dp)
            // Opens an Activity directly: Android 12 bans broadcast -> activity trampolines.
            .clickable(actionStartActivity<QuickAddActivity>()),
    )
}

@Composable
private fun TagGrid(todos: List<TodoUi>, filter: String?, lazy: Boolean) {
    val pending = todos.filter { !it.done }
    // Counts are of PENDING work: a tag whose tasks are all done should not shout for attention.
    val tags = pending.mapNotNull { it.project?.takeIf(String::isNotBlank) }
        .groupingBy { it }.eachCount()
        .toList().sortedByDescending { it.second }

    val tiles = listOf(ALL_TAGS_LABEL to pending.size) + tags

    @Composable
    fun tile(name: String, count: Int) {
        val all = name == ALL_TAGS_LABEL
        TagTile(
            name = name,
            count = count,
            all = all,
            // The face must show which filter is in force — the widget you flipped over is still
            // the same filtered widget.
            selected = if (all) filter == null else filter == name,
        )
    }

    if (lazy) {
        LazyVerticalGrid(gridCells = GridCells.Fixed(2)) {
            items(tiles, itemId = { it.first.hashCode().toLong() }) { (name, count) ->
                tile(name, count)
            }
        }
    } else {
        Column {
            tiles.chunked(2).forEach { pair ->
                Row(GlanceModifier.fillMaxWidth()) {
                    pair.forEach { (name, count) ->
                        Column(GlanceModifier.defaultWeight()) { tile(name, count) }
                    }
                    if (pair.size == 1) Spacer(GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun TagTile(name: String, count: Int, all: Boolean, selected: Boolean) {
    // Every tile sits on the same quiet surface (InkRaised + hairline, the add dock's language).
    // The one in force wears a thin cyan ring — a ring means "current"; the filled cyan circle
    // stays reserved for a completed check. «Todas» is first and drops the #.
    Column(
        GlanceModifier
            .fillMaxWidth()
            .padding(2.dp)
            .cornerRadius(12.dp)
            .background(ImageProvider(
                if (selected) R.drawable.widget_tile_active else R.drawable.widget_tile
            ))
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
            style = TextStyle(color = chalk, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        )
        Text(
            "$count",
            style = TextStyle(color = chalk3, fontSize = 11.sp),
        )
    }
}

@Composable
private fun TodoRow(todo: TodoUi, showTag: Boolean = true, compact: Boolean = false) {
    Row(
        GlanceModifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 6.dp)
            // THE tap fix. v1 gave every row the SAME mutable PendingIntent template and relied on
            // per-row fill-in extras merging into it; launchers that drop the merge left
            // `getStringExtra(EXTRA_ID) ?: return` and the checkbox did nothing. Glance registers one
            // typed action per row — no template, nothing to merge — and the whole row is the target.
            // The action carries the user's INTENT (the negation of what this row is showing), not
            // just the id: deriving the new value from Room at tap time inverted the tap whenever
            // Room had moved under a stale render («no puedo destickearlo», 2026-07-09).
            .clickable(
                actionRunCallback<ToggleTodoAction>(
                    actionParametersOf(
                        ToggleTodoAction.idKey to todo.id,
                        ToggleTodoAction.doneKey to !todo.done,
                    )
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
        // The drawables are the app's TaskCheck, frozen: a Chalk2 ring that fills with cyan.
        Image(
            provider = ImageProvider(if (todo.done) R.drawable.ic_check_on else R.drawable.ic_check_off),
            contentDescription = if (todo.done) DONE_ICON_DESCRIPTION else PENDING_ICON_DESCRIPTION,
            modifier = GlanceModifier.size(20.dp),
        )
        Spacer(GlanceModifier.width(10.dp))
        Text(
            todo.title,
            // Compact placements: one full-width line per task, or a single wrapped title eats
            // the only row that fits and the widget glances as nothing.
            maxLines = if (compact) 1 else 2,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(
                color = if (todo.done) chalk3 else chalk,
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
                    style = TextStyle(color = chalk3, fontSize = 9.sp),
                )
            }
        }
    }
}
