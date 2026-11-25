package dev.aiauto.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.aiauto.AppLog
import dev.aiauto.TaskService
import dev.aiauto.graph

/**
 * Wakes the app when a task saved for later is due, so it starts even if the app was closed. The alarm is inexact,
 * which needs no special permission and may run a few minutes late while the phone sleeps. It's set again after a
 * restart, since Android forgets alarms then.
 */
class StartLaterAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The app's start already sets the alarm again; after a restart there's nothing else to do.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching {
            val g = context.graph
            g.engine.startDue()
            if (g.hasActiveWork()) TaskService.ensureRunning(context)
            schedule(context, g.engine.nextStartMillis())
        }.onFailure { AppLog.e("Couldn't start a task saved for later", it) }
    }

    companion object {
        private const val ACTION_DUE = "dev.aiauto.START_LATER_DUE"

        /** Sets the alarm for [atMillis], or cancels it when there's nothing waiting. */
        fun schedule(context: Context, atMillis: Long?) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, StartLaterAlarm::class.java).setAction(ACTION_DUE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            runCatching {
                if (atMillis == null) alarms.cancel(pending)
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            }.onFailure { AppLog.w("Couldn't set the alarm for a task saved for later", it) }
        }
    }
}
