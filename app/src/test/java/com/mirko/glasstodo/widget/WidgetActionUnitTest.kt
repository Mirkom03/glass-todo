package com.mirko.glasstodo.widget

import androidx.glance.GlanceTheme
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.testing.unit.assertIsChecked
import androidx.glance.appwidget.testing.unit.assertIsNotChecked
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.assertHasStartActivityClickAction
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import com.mirko.glasstodo.domain.TodoUi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The regression test for the v1 bug. No launcher, no emulator: it renders the exact composable the
 * real widget renders and asserts the click actions that get registered.
 *
 * Robolectric is REQUIRED even though `runGlanceAppWidgetUnitTest` is a "JVM" API: Glance's
 * `ActionParameters` is backed by a real `android.os.Bundle`, so on the plain JVM every test dies
 * with "Method putInt in android.os.BaseBundle not mocked".
 */
@RunWith(RobolectricTestRunner::class)
class WidgetActionUnitTest {

    private val pan = TodoUi(id = "id-1", title = "Comprar pan", project = "casa", done = false)
    private val luz = TodoUi(id = "id-2", title = "Pagar luz", project = null, done = true)

    @Test
    fun everyRowCarriesItsOwnId() = runGlanceAppWidgetUnitTest {
        provideComposable { GlanceTheme { WidgetGlanceContent(listOf(pan, luz)) } }

        // v1 shared ONE mutable PendingIntent template across rows and merged per-row fill-in extras
        // into it; when a launcher dropped the merge the id arrived null and the tap did nothing.
        // Here each row must own a separate action carrying exactly its own id. `onNode` also fails
        // if a matcher hits more than one node, so this proves the two actions are distinct.
        onNode(
            hasRunCallbackClickAction<ToggleTodoAction>(actionParametersOf(ToggleTodoAction.idKey to "id-1"))
        ).assertExists()
        onNode(
            hasRunCallbackClickAction<ToggleTodoAction>(actionParametersOf(ToggleTodoAction.idKey to "id-2"))
        ).assertExists()
        onNode(
            hasRunCallbackClickAction<ToggleTodoAction>(actionParametersOf(ToggleTodoAction.idKey to "id-3"))
        ).assertDoesNotExist()
    }

    @Test
    fun doneStateRendersPerRow() = runGlanceAppWidgetUnitTest {
        provideComposable { GlanceTheme { WidgetGlanceContent(listOf(pan, luz)) } }

        onNode(hasText("Comprar pan")).assertIsNotChecked()
        onNode(hasText("Pagar luz")).assertIsChecked()
    }

    @Test
    fun projectTagIsShownNextToTheTitle() = runGlanceAppWidgetUnitTest {
        provideComposable { GlanceTheme { WidgetGlanceContent(listOf(pan)) } }

        onNode(hasText("#casa")).assertExists()   // hasText matches on substring
    }

    @Test
    fun addButtonStartsQuickAddActivity_notABroadcast() = runGlanceAppWidgetUnitTest {
        provideComposable { GlanceTheme { WidgetGlanceContent(emptyList()) } }

        // Android 12 bans broadcast -> activity trampolines; this must be a start-activity action.
        onNode(hasContentDescription(ADD_BUTTON_DESCRIPTION))
            .assertHasStartActivityClickAction<QuickAddActivity>()
    }

    @Test
    fun emptyStateIsRenderedInsteadOfNothing() = runGlanceAppWidgetUnitTest {
        provideComposable { GlanceTheme { WidgetGlanceContent(emptyList()) } }

        onNode(hasText("Sin tareas")).assertExists()          // plain text, no click action on it
        onNode(hasText("Comprar pan")).assertDoesNotExist()
    }
}
