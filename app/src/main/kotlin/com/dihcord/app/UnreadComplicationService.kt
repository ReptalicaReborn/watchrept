package com.dihcord.app

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/** Watch-face complication showing the total unread mention count. */
class UnreadComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) build(3) else null

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        if (request.complicationType == ComplicationType.SHORT_TEXT) {
            build(UnreadSummary.read(this).total)
        } else null

    private fun build(count: Int): ComplicationData {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(count.toString()).build(),
            PlainComplicationText.Builder(if (count == 1) "1 unread mention" else "$count unread mentions").build()
        )
            // A small chat bubble next to the number, so it reads as "Discord" on a busy watch face.
            .setMonochromaticImage(
                MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.chat)).build()
            )
            .setTapAction(open)
            .build()
    }
}
