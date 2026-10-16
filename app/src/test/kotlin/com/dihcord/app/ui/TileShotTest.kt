package com.dihcord.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.tiles.renderer.TileRenderer
import com.dihcord.app.UnreadSummary
import com.dihcord.app.UnreadTileService
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/** Draws the unread tile with Google's tile renderer, as the watch would, into app/build/ui-shots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w216dp-h216dp-round-watch-xhdpi")
class TileShotTest {

    private fun shot(name: String, counts: UnreadSummary.Counts) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        // Tiles sit on black on the watch; the test activity's window is light.
        val parent = FrameLayout(activity).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        activity.setContentView(parent)
        val layout = LayoutElementBuilders.Layout.Builder()
            .setRoot(UnreadTileService.layout(activity.packageName, counts))
            .build()
        val resources = ResourceBuilders.Resources.Builder().setVersion(UnreadTileService.RESOURCES_VERSION).build()
        val renderer = TileRenderer(activity, { it.run() }, { })
        val future = renderer.inflateAsync(layout, resources, parent)
        repeat(20) { if (!future.isDone) shadowOf(activity.mainLooper).idle() }
        future.get(5, TimeUnit.SECONDS)
        shadowOf(activity.mainLooper).idle()

        val view: View = activity.window.decorView
        val square = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(square).apply { drawColor(android.graphics.Color.BLACK) })
        val round = Bitmap.createBitmap(square.width, square.height, Bitmap.Config.ARGB_8888)
        Canvas(round).apply {
            drawColor(android.graphics.Color.BLACK)
            clipPath(Path().apply { addCircle(square.width / 2f, square.height / 2f, square.width / 2f, Path.Direction.CW) })
            drawBitmap(square, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
        }
        val dir = File("build/ui-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { round.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun tile() = shot("tile", UnreadSummary.Counts(dm = 2, server = 3))
    @Test fun tileEmpty() = shot("tile_empty", UnreadSummary.Counts(dm = 0, server = 0))
}
