package com.dihcord.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.dihcord.app.screens.LocalTimeSource
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.TimeSource
import com.dihcord.app.DihcordTheme
import java.io.File

/** A clock that always reads 11:39, so screenshots don't change from run to run. */
object FixedTime : TimeSource {
    @Composable
    override fun currentTime(): String = "11:39"
}

/**
 * Renders [content] the way MainActivity does (theme, app scaffold, time text) and saves it as
 * `app/build/ui-shots/<name>.png`, masked to the round watch face.
 */
fun AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>.watchShot(
    name: String,
    /** Real time to let background work (e.g. the offline network calls failing) finish first. */
    settleMs: Long = 0,
    /** Upward swipes to do before the picture, to show what's further down a list. */
    swipes: Int = 0,
    /** Taps etc. to do before the swipes (e.g. open a picker). */
    act: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>.() -> Unit = {},
    content: @Composable () -> Unit
) {
    setContent {
        DihcordTheme {
            CompositionLocalProvider(LocalTimeSource provides FixedTime) {
                AppScaffold(timeText = { TimeText(timeSource = FixedTime) }) { content() }
            }
        }
    }
    waitForIdle()
    var waited = 0L
    while (waited < settleMs) {
        Thread.sleep(100)
        waited += 100
        mainClock.advanceTimeBy(100)
        waitForIdle()
    }
    act()
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    repeat(swipes) {
        onRoot().performTouchInput { swipeUp(startY = height * 0.85f, endY = height * 0.15f, durationMillis = 300) }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }
    // Draw the window by hand: captureToImage() waits for a hardware frame that Robolectric never delivers.
    val view = activity.window.decorView
    val square = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    runOnUiThread { view.draw(Canvas(square)) }
    val round = Bitmap.createBitmap(square.width, square.height, Bitmap.Config.ARGB_8888)
    Canvas(round).apply {
        drawColor(android.graphics.Color.BLACK)
        clipPath(Path().apply {
            addCircle(square.width / 2f, square.height / 2f, square.width / 2f, Path.Direction.CW)
        })
        drawBitmap(square, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
    }
    val dir = File("build/ui-shots").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { round.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
