package com.mirko.glasstodo.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.mirko.glasstodo.domain.TodoUi
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the REAL widget content to RemoteViews, inflates it, and writes a PNG. Without an emulator
 * this is the only way to actually look at the widget before shipping it.
 *
 * Output: app/build/outputs/roborazzi/
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class WidgetScreenshotTest {

    private val appContext: Context = ApplicationProvider.getApplicationContext()

    private val sample = listOf(
        TodoUi("1", "Cert FNMT antes de formatear", "personal", done = false, priority = 2),
        TodoUi("2", "Avisar a José de las reservas", "hacienda-verde", done = false, priority = 2),
        TodoUi("3", "Foto editorial inline + GSC", "balearic", done = false, priority = 1),
        TodoUi("4", "Copiar top-10 URLs a GSC", "drinks", done = false, priority = 0),
        TodoUi("5", "Widget Glance", "glass-todo", done = true, priority = 2),
    )

    private fun capture(name: String, size: DpSize, content: @androidx.compose.runtime.Composable () -> Unit) {
        val remoteViews = runBlocking {
            GlanceRemoteViews().compose(context = appContext, size = size, content = content).remoteViews
        }

        // Roborazzi refuses to capture a View whose context is not an Activity, so inflate the
        // RemoteViews INTO a real (Robolectric) Activity rather than into a bare app-context parent.
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val parent = FrameLayout(activity)
        val view: View = remoteViews.apply(activity, parent)

        val density = activity.resources.displayMetrics.density
        val w = (size.width.value * density).toInt()
        val h = (size.height.value * density).toInt()
        parent.addView(view, ViewGroup.LayoutParams(w, h))
        activity.setContentView(parent, ViewGroup.LayoutParams(w, h))

        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)

        view.captureRoboImage("build/outputs/roborazzi/widget_$name.png")
    }

    // lazy = false: a RemoteViews collection adapter is not populated outside an AppWidgetHost, so a
    // LazyColumn would screenshot as an empty widget. Only the container differs; the rows are real.
    // No GlanceTheme wrapper: the content carries the app's palette itself, like the real widget.

    @Test fun list() = capture("list", DpSize(250.dp, 250.dp)) {
        WidgetGlanceContent(sample, lazy = false)
    }

    @Test fun tags() = capture("tags", DpSize(250.dp, 250.dp)) {
        WidgetGlanceContent(sample, showTags = true, lazy = false)
    }

    @Test fun filtered() = capture("filtered", DpSize(250.dp, 250.dp)) {
        WidgetGlanceContent(sample, filter = "personal", lazy = false)
    }

    @Test fun small() = capture("small", DpSize(180.dp, 110.dp)) {
        // compact mirrors what the real widget derives from LocalSize at this bucket.
        WidgetGlanceContent(sample, compact = true, lazy = false)
    }

    @Test fun empty() = capture("empty", DpSize(250.dp, 250.dp)) {
        WidgetGlanceContent(emptyList(), lazy = false)
    }

    @Test fun alldone() = capture("alldone", DpSize(250.dp, 250.dp)) {
        // Everything resolved: the header must pay off the app's name with «Listo.» in cyan.
        WidgetGlanceContent(sample.map { it.copy(done = true) }, lazy = false)
    }
}
