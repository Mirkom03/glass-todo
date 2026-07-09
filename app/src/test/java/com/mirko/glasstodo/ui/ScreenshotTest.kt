package com.mirko.glasstodo.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.mirko.glasstodo.domain.TodoUi
import com.mirko.glasstodo.ui.theme.ListoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real screen to a PNG on the JVM. This is not a golden-diff gate — it is how a design
 * change gets looked at at all, since there is no emulator or device in this environment.
 *
 * Output: app/build/outputs/roborazzi/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private val sample = listOf(
        TodoUi("1", "Cert FNMT antes de formatear", "personal", done = false, priority = 2),
        TodoUi("2", "Avisar a José de las reservas", "hacienda-verde", done = false, priority = 2),
        TodoUi("3", "Foto editorial inline + GSC", "balearic", done = false, priority = 1),
        TodoUi("4", "Copiar top-10 URLs a GSC", "drinks", done = false, priority = 0),
        TodoUi("5", "Verificar nº Zadarma", "personal", done = false, priority = 0, pending = true),
        TodoUi("6", "Widget Glance", "glass-todo", done = true, priority = 2),
        TodoUi("7", "Copy del hero", "aide", done = true, priority = 0),
    )

    private fun capture(name: String, state: TodoUiState) {
        compose.setContent {
            ListoTheme {
                TodoScreenContent(state = state, onToggle = { _, _ -> }, onAdd = { _, _ -> })
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/app_$name.png")
    }

    @Test fun content() = capture("content", TodoUiState(todos = sample, isLoading = false))
    @Test fun empty() = capture("empty", TodoUiState(isLoading = false))
    @Test fun loading() = capture("loading", TodoUiState(isLoading = true))
}
