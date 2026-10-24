package com.echoradio.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder

/** What the notification and lock screen show. Set by MainActivity, read by PlaybackService. */
object NowPlaying {
    @Volatile var title = ""
    @Volatile var artist = ""
    @Volatile var djLine = ""
    @Volatile var playing = false
    @Volatile var art: Bitmap? = null
    /** Album colour for the widget background. */
    @Volatile var color: Int = AlbumColors.DEFAULT.page
    /** Receives "play", "pause", "next" and "prev" from the notification and lock screen. */
    @Volatile var onCommand: ((String) -> Unit)? = null
}

/**
 * Keeps EchoRadio running with the screen off and shows lock-screen / notification controls.
 * The DJ itself lives in MainActivity; this service just keeps it alive and relays button presses.
 */
class PlaybackService : Service() {

    private lateinit var session: MediaSession

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Now playing", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
        session = MediaSession(this, "EchoRadio").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { NowPlaying.onCommand?.invoke("play") }
                override fun onPause() { NowPlaying.onCommand?.invoke("pause") }
                override fun onSkipToNext() { NowPlaying.onCommand?.invoke("next") }
                override fun onSkipToPrevious() { NowPlaying.onCommand?.invoke("prev") }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_CMD)?.let { NowPlaying.onCommand?.invoke(it) }
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, n)
        if (NowPlaying.onCommand == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        return START_NOT_STICKY
    }

    private fun action(icon: Int, label: String, cmd: String, req: Int) = Notification.Action.Builder(
        android.graphics.drawable.Icon.createWithResource(this, icon), label,
        PendingIntent.getService(this, req, Intent(this, PlaybackService::class.java).putExtra(EXTRA_CMD, cmd),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    ).build()

    private fun build(): Notification {
        val playing = NowPlaying.playing
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, NowPlaying.title.ifBlank { "EchoRadio" })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, NowPlaying.artist)
            .apply { NowPlaying.art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
            .build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build())
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(NowPlaying.title.ifBlank { "EchoRadio" })
            .setContentText(NowPlaying.artist)
            .setSubText(NowPlaying.djLine.take(80).ifBlank { null })
            .setLargeIcon(NowPlaying.art)
            .setContentIntent(open)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_prev, "Previous", "prev", 1))
            .addAction(if (playing) action(R.drawable.ic_pause, "Pause", "pause", 2) else action(R.drawable.ic_play, "Play", "play", 2))
            .addAction(action(R.drawable.ic_next, "Next", "next", 3))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onDestroy() {
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "now_playing"
        private const val ID = 7
        private const val EXTRA_CMD = "cmd"

        /** Shows or refreshes the notification with whatever is in [NowPlaying]. */
        fun update(context: Context) {
            runCatching { context.startForegroundService(Intent(context, PlaybackService::class.java)) }
            EchoWidget.update(context)
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
            EchoWidget.update(context)
        }
    }
}
