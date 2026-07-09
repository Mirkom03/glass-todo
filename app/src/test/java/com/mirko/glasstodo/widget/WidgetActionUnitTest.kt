package com.mirko.glasstodo.widget

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
 * No GlanceTheme wrapper anywhere — the content carries the app's own palette and reads nothing from
 * the system theme. That absence is itself the fix for «parecen totalmente diferentes».
 *
 * Robolectric is REQUIRED even though `runGlanceAppWidgetUnitTest` is a "JVM" API: Glance's
 * `ActionParameters` is backed by a real `android.os.Bundle`, so on the plain JVM every test dies
 * with "Method putInt in android.os.BaseBundle not mocked".
 */
@RunWith(RobolectricTestRunner::class)
class WidgetActionUnitTest {

    private val pan = TodoUi(id = "id-1", title = "Comprar pan", project = "casa", done = false)
    private val luz = TodoUi(id = "id-2", title = "Pagar luz", project = null, done = true)
    private val aide = TodoUi(id = "id-3", title = "Landing C2", project = "aide", done = false, priority = 2)

    @Test
    fun everyRowCarriesItsOwnId() = runGlanceAppWidgetUnitTest {
        provideComposable { WidgetGlanceContent(listOf(pan, luz)) }

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
        provideComposable { WidgetGlanceContent(listOf(pan, luz)) }

        onNode(isChecked()).assertDoesNotExist()
        onNode(isNotChecked()).assertDoesNotExist()
    }

    @Test
    fun doneStateIsShownByTheRowIcon() = runGlanceAppWidgetUnitTest {
        provideComposable { WidgetGlanceContent(listOf(pan, luz)) }

        onNode(hasContentDescription(PENDING_ICON_DESCRIPTION)).assertExists()   // "Comprar pan"
        onNode(hasContentDescription(DONE_ICON_DESCRIPTION)).assertExists()      // "Pagar luz"
    }

    @Test
    fun projectTagIsShownAsItsOwnLabel() = runGlanceAppWidgetUnitTest {
        provideComposable { WidgetGlanceContent(listOf(pan)) }

        onNode(hasTextEqualTo("CASA")).assertExists()          // typographic label, not glued to the title
        onNode(hasTextEqualTo("Comprar pan")).assertExists()
    }

    @Test
    fun addButtonStartsQuickAddActivity_notABroadcast() = runGlanceAppWidgetUnitTest {
        provideComposable { WidgetGlanceContent(emptyList()) }

        // Android 12 bans broadcast -> activity trampolines; this must be a start-activity action.
        onNode(hasContentDescription(ADD_BUTTON_DESCRIPTION))
            .assertHasStartActivityClickAction<QuickAddActivity>()
    }

    // ---- the header is the summary, same as the app ----

    @Test
    fun headerCountsPendingWork_likeTheAppHeader() = runGlanceAppWidgetUnitTest {
        provideComposable { WidgetGlanceContent(listOf(pan, aide, luz)) }

        // luz is done: 2 pending. The count and its label, not an app-name title.
        onNode(hasTextEqualTo("2")).assertExists()
        onNode(hasTextEqualTo("pendientes")).assertExists()
    }

    @Test
    fun zeroPendingShowsTheAppsOwnWord() = runGlanceAppWidgetUnitTest {
        provideComposable { WidgetGlanceContent(listOf(luz)) }

        // The payoff of the name, on the home screen too.
        onNode(hasTextEqualTo("Listo.")).assertExists()
    }

    // ---- concept B: the tags face ----

    @Test
    fun theHashButtonFlipsToTheTagsFace() = runGlanceAppWidgetUnitTest {
        provideComposable { WidgetGlanceContent(listOf(pan, aide)) }

        onNode(hasRunCallbackClickAction<ToggleTagsAction>()).assertExists()
        onNode(hasTextEqualTo("2")).assertExists()            // list face leads with the count
    }

    @Test
    fun tagsFaceOffersOneTilePerTag_plusAll_withPendingCounts() = runGlanceAppWidgetUnitTest {
        provideComposable { WidgetGlanceContent(listOf(pan, aide, luz), showTags = true) }

        onNode(hasTextEqualTo("ETIQUETAS")).assertExists()    // named in the app's section-rule voice
        // luz is done, so it contributes to no tile
        onNode(hasRunCallbackClickAction<SelectTagAction>(actionParametersOf(SelectTagAction.tagKey to ""))).assertExists()
        onNode(hasRunCallbackClickAction<SelectTagAction>(actionParametersOf(SelectTagAction.tagKey to "casa"))).assertExists()
        onNode(hasRunCallbackClickAction<SelectTagAction>(actionParametersOf(SelectTagAction.tagKey to "aide"))).assertExists()
        onNode(hasRunCallbackClickAction<SelectTagAction>(actionParametersOf(SelectTagAction.tagKey to "drinks"))).assertDoesNotExist()
        onNode(hasTextEqualTo(ALL_TAGS_LABEL)).assertExists()
    }

    @Test
    fun aFilterShowsOnlyThatTag_andThePillClearsIt() = runGlanceAppWidgetUnitTest {
        provideComposable { WidgetGlanceContent(listOf(pan, aide), filter = "aide") }

        onNode(hasTextEqualTo("Landing C2")).assertExists()
        onNode(hasTextEqualTo("Comprar pan")).assertDoesNotExist()
        // the pill is the way back: it carries a SelectTagAction with an empty tag
        onNode(hasTextEqualTo("#aide")).assertExists()
        onNode(hasRunCallbackClickAction<SelectTagAction>(actionParametersOf(SelectTagAction.tagKey to ""))).assertExists()
        // and the rows stop repeating a tag you already chose
        onNode(hasTextEqualTo("AIDE")).assertDoesNotExist()
    }

    @Test
    fun aFilterWithNothingInItSaysSo_insteadOfLookingBroken() = runGlanceAppWidgetUnitTest {
        provideComposable { WidgetGlanceContent(listOf(pan), filter = "drinks") }

        onNode(hasText("Nada en #drinks")).assertExists()
    }

    @Test
    fun emptyStateIsRenderedInsteadOfNothing() = runGlanceAppWidgetUnitTest {
        provideComposable { WidgetGlanceContent(emptyList()) }

        // The app's own words for the same state — one voice across surfaces.
        onNode(hasText("Nada que hacer")).assertExists()
        onNode(hasText("Comprar pan")).assertDoesNotExist()
    }

    @Test
    fun compactFaceDropsTheTagLabel_soTheTitleKeepsTheWholeLine() = runGlanceAppWidgetUnitTest {
        provideComposable { WidgetGlanceContent(listOf(pan), compact = true) }

        onNode(hasTextEqualTo("Comprar pan")).assertExists()
        onNode(hasTextEqualTo("CASA")).assertDoesNotExist()
    }
}
