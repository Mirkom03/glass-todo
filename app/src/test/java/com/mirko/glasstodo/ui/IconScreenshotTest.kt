package com.mirko.glasstodo.ui

import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.ComponentActivity
import com.github.takahirom.roborazzi.captureRoboImage
import com.mirko.glasstodo.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The launcher icon is the most-seen surface of the app and the one nobody ever looks at before
 * shipping. This renders the real adaptive icon so it can be judged at the size it will be seen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xhdpi")
class IconScreenshotTest {

    private fun capture(name: String, resId: Int, sizePx: Int) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val image = ImageView(activity).apply {
            setImageResource(resId)
            layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
        }
        activity.setContentView(image, ViewGroup.LayoutParams(sizePx, sizePx))
        image.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(sizePx, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(sizePx, android.view.View.MeasureSpec.EXACTLY),
        )
        image.layout(0, 0, sizePx, sizePx)
        image.captureRoboImage("build/outputs/roborazzi/icon_$name.png")
    }

    @Test fun launcher() = capture("launcher", R.mipmap.ic_launcher, 288)

    /** The size it actually gets on a home screen. If the mark dies here, the mark is wrong. */
    @Test fun launcherSmall() = capture("launcher_small", R.mipmap.ic_launcher, 96)
}
