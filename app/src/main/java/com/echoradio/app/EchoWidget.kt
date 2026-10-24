package com.echoradio.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews

/** The 4×2 home-screen widget: cover, song, the DJ's latest line, and controls. */
class EchoWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    companion object {
        const val EXTRA_OPEN_CHAT = "open_chat"

        /** Redraws every EchoRadio widget from [NowPlaying]. Cheap to call often. */
        fun update(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, EchoWidget::class.java))
            if (ids.isNotEmpty()) runCatching { mgr.updateAppWidget(ids, views(context)) }
        }

        private fun views(context: Context): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_player)
            val alive = NowPlaying.onCommand != null && NowPlaying.title.isNotBlank()
            v.setTextViewText(R.id.wDj, Prefs(context).djName.uppercase() + if (alive) "  ·  LIVE" else "")
            v.setTextViewText(R.id.wTitle, if (alive) NowPlaying.title else "Your DJ is ready")
            v.setTextViewText(R.id.wArtist, if (alive) NowPlaying.artist else "Tap DJ to start a set")
            v.setTextViewText(R.id.wLine, if (alive) NowPlaying.djLine else "")
            v.setImageViewResource(R.id.wPlay, if (alive && NowPlaying.playing) R.drawable.ic_pause_dark else R.drawable.ic_play_dark)
            val art = NowPlaying.art
            if (alive && art != null) v.setImageViewBitmap(R.id.wCover, small(art)) else v.setImageViewResource(R.id.wCover, R.drawable.ic_radio)
            v.setInt(R.id.wBg, "setColorFilter", if (alive) NowPlaying.color else AlbumColors.DEFAULT.page)

            // Controls go to the playback service; if the app isn't running they open it instead
            fun cmd(c: String, req: Int) = PendingIntent.getForegroundService(context, 100 + req,
                Intent(context, PlaybackService::class.java).putExtra("cmd", c),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val open = PendingIntent.getActivity(context, 200,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val chat = PendingIntent.getActivity(context, 201,
                Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_CHAT, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            if (alive) {
                v.setOnClickPendingIntent(R.id.wPrev, cmd("prev", 1))
                v.setOnClickPendingIntent(R.id.wPlay, cmd(if (NowPlaying.playing) "pause" else "play", 2))
                v.setOnClickPendingIntent(R.id.wNext, cmd("next", 3))
            } else {
                listOf(R.id.wPrev, R.id.wPlay, R.id.wNext).forEach { v.setOnClickPendingIntent(it, chat) }
            }
            v.setOnClickPendingIntent(R.id.wDjBtn, chat)
            v.setOnClickPendingIntent(R.id.wCover, open)
            v.setOnClickPendingIntent(R.id.wTitle, open)
            return v
        }

        /** Widgets have a small memory budget, so the cover is scaled down. */
        private fun small(b: Bitmap): Bitmap = if (b.width <= 200) b else Bitmap.createScaledBitmap(b, 200, 200 * b.height / b.width, true)
    }
}
