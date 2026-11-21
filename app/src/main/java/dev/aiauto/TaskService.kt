package dev.aiauto

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while Claude is working, so status keeps arriving with the phone
 * locked in a mount or after Android Auto disconnects. Stops itself once no task is running.
 */
class TaskService : Service() {
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        // Android can refuse a foreground start (background limits, missing permission). Tasks still run
        // while the app is open, and resume when it next starts, so stop quietly instead of crashing.
        val started = runCatching {
            ServiceCompat.startForeground(this, dev.aiauto.notify.Notifier.WORKING_ID, graph.notifier.workingNotification(), type)
        }
        if (started.isFailure) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Keep the CPU awake while Claude works, so the stream isn't paused when the screen turns off
        // after Android Auto disconnects. The timeout is a safety net; the watcher renews it.
        holdWakeLock()
        if (watcher?.isActive != true) {
            watcher = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                delay(5_000)
                var sinceRefresh = 0L
                // Stay up while Claude works, while a task cut off by a lost connection is being retried, and while
                // a new pull request's checks are running so the driver hears the result and green pull requests get merged.
                while (stillNeeded()) {
                    // One bad round must not kill the watcher, or tasks would stop being retried and watched.
                    try {
                        graph.engine.resumeInterrupted()
                        graph.engine.checkStuck()
                        if (sinceRefresh >= PR_REFRESH_MILLIS) {
                            graph.engine.refreshPullRequests()
                            sinceRefresh = 0
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.w("AiAuto", "Task watcher round failed", e)
                    }
                    delay(5_000)
                    sinceRefresh += 5_000
                    holdWakeLock()
                }
                stopSelf()
            }
        }
        // If Android kills the app mid-task, restart it; the app re-attaches to running tasks on startup.
        return START_STICKY
    }

    private fun stillNeeded(): Boolean = try {
        graph.tasks.all().any { graph.engine.isRunning(it.id) } || graph.engine.isReconnecting() || graph.engine.isWatchingPullRequests()
    } catch (e: Throwable) {
        Log.w("AiAuto", "Couldn't read task state", e)
        false
    }

    private fun holdWakeLock() {
        runCatching {
            val lock = wakeLock ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiAuto:tasks")
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            lock.acquire(WAKE_LOCK_MILLIS)
        }
    }

    /**
     * Android 15 gives a data-sync foreground service about six hours a day. When that runs out the service has to
     * stop within seconds, or Android crashes the app. Tasks keep running while the app is open, and resume on restart.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w("AiAuto", "Foreground time ran out; stopping the task service")
        watcher?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        watcher?.cancel()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    companion object {
        private const val PR_REFRESH_MILLIS = 30_000L
        private const val WAKE_LOCK_MILLIS = 10 * 60 * 1000L

        fun ensureRunning(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TaskService::class.java)) }
                .onFailure { Log.w("AiAuto", "Couldn't start the task service", it) }
        }

        /** True when Android lets the app keep working in the background without battery limits. */
        fun isUnrestricted(context: Context): Boolean =
            runCatching { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    }
}
