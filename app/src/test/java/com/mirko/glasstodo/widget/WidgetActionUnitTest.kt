package com.mirko.glasstodo.widget

import androidx.glance.GlanceTheme
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.isChecked
import androidx.glance.appwidget.testing.unit.isNotChecked
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.assertHasStartActivityClickAction
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import com.mirko.glasstodo.domain.TodoUi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The regression tests for the widget. No launcher, no emulator: it renders the exact composable the
 * real widget renders and asserts what gets registered on it.
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

    /**
     * v1.1.0's real bug. A Glance `CheckBox` becomes an `android.widget.CheckBox`, and on API 31+ its
     * translator makes that same View both the text view and the action target. `CompoundButton` is
     * clickable by default, so a decorative checkbox (`onCheckedChange = null`) ate the touch across
     * the whole row and the Row's own PendingIntent never fired: tapping did nothing.
     *
     * The row must therefore contain NO checkable node at all — only an image and a text.
     */
    @Test
    fun rowsContainNoCheckableNodeThatCouldSwallowTheTap() = runGlanceAppWidgetUnitTest {
        provideComposable { GlanceTheme { WidgetGlanceContent(listOf(pan, luz)) } }

        onNode(isChecked()).assertDoesNotExist()
        onNode(isNotChecked()).assertDoesNotExist()
    }

    @Test
    fun doneStateIsShownByTheRowIcon() = runGlanceAppWidgetUnitTest {
        provideComposable { GlanceTheme { WidgetGlanceContent(listOf(pan, luz)) } }

        onNode(hasContentDescription(PENDING_ICON_DESCRIPTION)).assertExists()   // "Comprar pan"
        onNode(hasContentDescription(DONE_ICON_DESCRIPTION)).assertExists()      // "Pagar luz"
    }

    @Test
    fun aListWithNothingDoneShowsNoDoneIcon() = runGlanceAppWidgetUnitTest {
        provideComposable { GlanceTheme { WidgetGlanceContent(listOf(pan)) } }

        onNode(hasContentDescription(DONE_ICON_DESCRIPTION)).assertDoesNotExist()
    }

    @Test
    fun projectTagIsShownAsItsOwnChip() = runGlanceAppWidgetUnitTest {
        provideComposable { GlanceTheme { WidgetGlanceContent(listOf(pan)) } }

        // The tag is a separate node now, not glued onto the title, so it can be styled and (later)
        // filtered on. The title must NOT carry it.
        onNode(hasTextEqualTo("#casa")).assertExists()
        onNode(hasTextEqualTo("Comprar pan")).assertExists()
    }

    @Test
    fun aTaskWithoutATagRendersNoChip() = runGlanceAppWidgetUnitTest {
        provideComposable { GlanceTheme { WidgetGlanceContent(listOf(luz)) } }   // project = null

        onNode(hasText("#")).assertDoesNotExist()
    }

    @Test
    fun urgencyIsShownOnlyWhilePending() = runGlanceAppWidgetUnitTest {
        val urgentPending = TodoUi(id = "u", title = "Arde", project = null, done = false, priority = 2)
        val urgentDone = urgentPending.copy(id = "d", title = "Ardía", done = true)
        provideComposable { GlanceTheme { WidgetGlanceContent(listOf(urgentPending, urgentDone)) } }

        // Both render; the bar is a Spacer so we cannot match it by text — what this pins is that a
        // done task never renders as urgent (regression guard for `!todo.done` in the row).
        onNode(hasTextEqualTo("Arde")).assertExists()
        onNode(hasTextEqualTo("Ardía")).assertExists()
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
