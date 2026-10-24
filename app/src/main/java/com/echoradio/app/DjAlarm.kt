package com.echoradio.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/** The DJ alarm: wakes you with the DJ and your music at a set time every day. */
object DjAlarm {
    const val EXTRA_ALARM = "dj_alarm"

    private fun intent(context: Context) = PendingIntent.getActivity(
        context, 42,
        Intent(context, MainActivity::class.java).putExtra(EXTRA_ALARM, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** The next time the alarm will go off (epoch ms). */
    fun nextTime(hour: Int, minute: Int): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= System.currentTimeMillis() + 5_000) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** Sets (or cancels) the alarm from the saved settings. */
    fun schedule(context: Context) {
        val prefs = Prefs(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = intent(context)
        am.cancel(pi)
        if (!prefs.alarmOn) return
        val at = nextTime(prefs.alarmHour, prefs.alarmMinute)
        runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(at, pi), pi) }
    }
}

/** Puts the DJ alarm back after the phone restarts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) DjAlarm.schedule(context)
    }
}
