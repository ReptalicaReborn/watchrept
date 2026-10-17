package com.dihcord.app

import android.content.ComponentName
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

/**
 * A tile summarising unread mentions: a ring around the edge split into DM and server mentions, the
 * total in the middle, and "All caught up" when there are none. Tapping it opens the app.
 */
class UnreadTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val counts = UnreadSummary.read(this)
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(packageName, counts)))
            .build()
        return immediate(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> =
        immediate(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

    private fun <T> immediate(value: T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(value)
            "immediate"
        }

    internal companion object {
        const val RESOURCES_VERSION = "2"
        private const val DM_COLOR = 0xFF5865F2.toInt()
        private const val SERVER_COLOR = 0xFFEB459E.toInt()
        private const val TRACK_COLOR = 0xFF2A2C36.toInt()
        private const val MUTED = 0xFFB5B9C4.toInt()
        /** The ring leaves a gap at the bottom, like Wear's own progress tiles. */
        private const val SWEEP = 300f

        /** The tile's layout for [counts]; also used by the screenshot tests. */
        fun layout(packageName: String, counts: UnreadSummary.Counts): LayoutElementBuilders.LayoutElement {
            val open = ModifiersBuilders.Clickable.Builder()
                .setId("open")
                .setOnClick(ActionBuilders.launchAction(ComponentName(packageName, MainActivity::class.java.name)))
                .build()
            return LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
                .addContent(ring(counts))
                .addContent(centre(counts))
                .build()
        }

        private fun ring(counts: UnreadSummary.Counts): LayoutElementBuilders.LayoutElement {
            val arc = LayoutElementBuilders.Arc.Builder()
                .setAnchorAngle(degrees(180f + (360f - SWEEP) / 2f))
                .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
            val total = counts.total
            if (total == 0) {
                arc.addContent(line(SWEEP, TRACK_COLOR))
            } else {
                val gap = if (counts.dm > 0 && counts.server > 0) 6f else 0f
                val dmSweep = (SWEEP - gap) * counts.dm / total
                if (counts.dm > 0) arc.addContent(line(dmSweep, DM_COLOR))
                if (gap > 0f) arc.addContent(LayoutElementBuilders.ArcSpacer.Builder().setLength(degrees(gap)).build())
                if (counts.server > 0) arc.addContent(line(SWEEP - gap - dmSweep, SERVER_COLOR))
            }
            return LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(6f)).build())
                        .build()
                )
                .addContent(arc.build())
                .build()
        }

        private fun line(length: Float, color: Int) =
            LayoutElementBuilders.ArcLine.Builder()
                .setLength(degrees(length))
                .setThickness(dp(8f))
                .setColor(argb(color))
                .build()

        private fun centre(counts: UnreadSummary.Counts): LayoutElementBuilders.LayoutElement {
            val column = LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(text("Dihcord", 13f, 0xFFB5BAFF.toInt(), bold = true))
            if (counts.total == 0) {
                column.addContent(spacer(6f))
                column.addContent(text("All caught up", 18f, 0xFFFFFFFF.toInt(), bold = true))
                column.addContent(text("No new mentions", 13f, MUTED))
            } else {
                column.addContent(text(counts.total.toString(), 44f, 0xFFFFFFFF.toInt(), bold = true))
                column.addContent(text(if (counts.total == 1) "mention" else "mentions", 13f, MUTED))
                column.addContent(spacer(6f))
                column.addContent(
                    LayoutElementBuilders.Spannable.Builder()
                        .addSpan(span("● ", DM_COLOR))
                        .addSpan(span(if (counts.dm == 1) "1 DM" else "${counts.dm} DMs", MUTED))
                        .addSpan(span("   ● ", SERVER_COLOR))
                        .addSpan(span(if (counts.server == 1) "1 server" else "${counts.server} servers", MUTED))
                        .build()
                )
            }
            return column.build()
        }

        private fun spacer(height: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(height)).build()

        private fun span(value: String, color: Int) =
            LayoutElementBuilders.SpanText.Builder()
                .setText(value)
                .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(DimensionBuilders.sp(13f)).setColor(argb(color)).build())
                .build()

        private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) =
            LayoutElementBuilders.Text.Builder()
                .setText(value)
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(DimensionBuilders.sp(sizeSp))
                        .setColor(argb(color))
                        .apply { if (bold) setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD) }
                        .build()
                )
                .build()
    }
}
