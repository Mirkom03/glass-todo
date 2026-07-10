package com.mirko.glasstodo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.mirko.glasstodo.domain.TodoUi
import com.mirko.glasstodo.ui.theme.Ink
import com.mirko.glasstodo.ui.theme.ListoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sheet's content, rendered on the JVM. Not a golden-diff gate — it is how the design gets looked
 * at at all, since there is no emulator here. Output: app/build/outputs/roborazzi/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class TaskDetailScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private fun capture(name: String, task: TodoUi) {
        compose.setContent {
            ListoTheme {
                TaskDetailContent(
                    task = task,
                    onToggle = {},
                    onSave = { _, _, _, _ -> },
                    onDelete = {},
                    modifier = Modifier.fillMaxSize().background(Ink),
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/detail_$name.png")
    }

    @Test fun withNotes() = capture(
        "with_notes",
        TodoUi("1", "Llamar a Texlink", "texlink", done = false, priority = 2,
            notes = "Confirmar el precio del informe antes del viernes. Si acepta, arrancar tracking."),
    )

    @Test fun withoutNotes() = capture(
        "without_notes",
        TodoUi("2", "Copiar top-10 URLs a GSC", "drinks", done = false, priority = 0),
    )

    @Test fun done() = capture(
        "done",
        TodoUi("3", "Widget Glance", "glass-todo", done = true, priority = 2, notes = "Entregado en v1.3.3."),
    )
}
