package com.mirko.glasstodo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mirko.glasstodo.domain.TodoUi
import com.mirko.glasstodo.domain.Urgency
import com.mirko.glasstodo.ui.theme.Chalk
import com.mirko.glasstodo.ui.theme.Chalk3
import com.mirko.glasstodo.ui.theme.Cyan
import com.mirko.glasstodo.ui.theme.Hairline
import com.mirko.glasstodo.ui.theme.Ink

/**
 * The whole task, on one surface. Rendered by TWO hosts — a ModalBottomSheet inside the app, and the
 * translucent widget/TaskDetailActivity — so the app and the widget cannot drift apart. That drift is
 * exactly what v1.3.0 shipped and v1.3.1 had to undo.
 *
 * Stateless except for the text being typed. Nothing is committed until [onSave]; the check writes
 * straight through, because a tick is not a draft.
 */
@Composable
fun TaskDetailContent(
    task: TodoUi,
    onToggle: (Boolean) -> Unit,
    onSave: (title: String, project: String?, priority: Int, notes: String?) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on the id: if realtime swaps the task under us, the drafts reset to the new task's values
    // instead of silently editing a different row.
    var title by rememberSaveable(task.id) { mutableStateOf(task.title) }
    var project by rememberSaveable(task.id) { mutableStateOf(task.project.orEmpty()) }
    var notes by rememberSaveable(task.id) { mutableStateOf(task.notes.orEmpty()) }
    var priority by rememberSaveable(task.id) { mutableIntStateOf(task.priority) }
    val urgency = Urgency.of(priority)

    Column(modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 28.dp)) {

        // The tick lives on the circle here too — the whole point of the redesign. The target is a
        // 44dp box; the circle hangs at its start edge so it lands on the same left margin as the
        // title, the tag and the labels below.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clickable { onToggle(!task.done) },
                contentAlignment = Alignment.CenterStart,
            ) {
                TaskCheck(task.done)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                if (task.done) "HECHA" else "PENDIENTE",
                color = if (task.done) Cyan else Chalk3,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.14.em,
            )
        }

        Spacer(Modifier.height(14.dp))

        Field(value = title, onValue = { title = it }, placeholder = "Título", size = 22.sp)
        Spacer(Modifier.height(8.dp))
        Field(value = project, onValue = { project = it }, placeholder = "Etiqueta", size = 13.sp, prefix = "#")

        Spacer(Modifier.height(18.dp))
        Rule()
        Spacer(Modifier.height(18.dp))

        Label("URGENCIA")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Urgency.entries.forEach { level ->
                UrgencyChip(level = level, selected = urgency == level, onClick = { priority = level.priority })
            }
        }

        Spacer(Modifier.height(20.dp))

        Label("DESCRIPCIÓN")
        Spacer(Modifier.height(10.dp))
        Field(
            value = notes,
            onValue = { notes = it },
            placeholder = "Lo que haga falta recordar",
            size = 15.sp,
            singleLine = false,
            modifier = Modifier.heightIn(min = 96.dp),
        )

        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Zero horizontal content padding: a TextButton's default 24dp would push "Borrar" off the
            // one left edge everything else on this sheet shares. The 48dp min height survives.
            TextButton(onClick = onDelete, contentPadding = FlushButtonPadding) {
                Text("Borrar", color = Chalk3, fontSize = 14.sp)
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                enabled = title.isNotBlank(),
                contentPadding = FlushButtonPadding,
                onClick = { onSave(title.trim(), project.blankToNull(), priority, notes.blankToNull()) },
            ) {
                Text(
                    "Guardar",
                    color = if (title.isNotBlank()) Cyan else Chalk3,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private val FlushButtonPadding = PaddingValues(horizontal = 0.dp, vertical = 12.dp)

/** "" and null both mean "no tag" / "no description"; the store and the server only speak null. */
private fun String.blankToNull(): String? = trim().takeIf { it.isNotBlank() }

@Composable
private fun Label(text: String) = Text(
    text,
    color = Chalk3,
    fontSize = 10.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.14.em,
)

/** The one divider this app ever draws. */
@Composable
private fun Rule() = Spacer(Modifier.fillMaxWidth().height(1.dp).background(Hairline))

/**
 * BasicTextField, not Material3's TextField: that one carries ~16dp of internal padding and a 56dp
 * minimum height it will not surrender, which indents the title away from the check above it and
 * blows dead space between the fields. Everything on this sheet has to share one left edge.
 */
@Composable
private fun Field(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    size: TextUnit,
    prefix: String = "",
    singleLine: Boolean = true,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = singleLine,
        textStyle = TextStyle(color = Chalk, fontSize = size),
        cursorBrush = SolidColor(Cyan),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { field ->
            // A one-line field centres its prefix on the text; a wrapping one has to hang it at the top.
            Row(verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top) {
                if (prefix.isNotEmpty()) {
                    Text(prefix, color = Chalk3, fontSize = size)
                }
                Box {
                    if (value.isEmpty()) Text(placeholder, color = Chalk3, fontSize = size)
                    field()
                }
            }
        },
    )
}

/**
 * The in-app host. The Activity host (widget/TaskDetailActivity) renders the SAME [TaskDetailContent];
 * only the dismiss wiring differs (there it is `finish()`).
 *
 * The default drag handle stays: it is the affordance that says "arrástrame para cerrar".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailSheet(
    task: TodoUi,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSave: (title: String, project: String?, priority: Int, notes: String?) -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ink,
    ) {
        TaskDetailContent(task = task, onToggle = onToggle, onSave = onSave, onDelete = onDelete)
    }
}
