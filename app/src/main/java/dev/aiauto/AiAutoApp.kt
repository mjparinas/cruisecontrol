package dev.aiauto

import android.app.Application
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.aiauto.data.ErrorStore
import dev.aiauto.update.Updater

class AiAutoApp : Application() {
    lateinit var graph: Graph
        private set
    lateinit var updater: Updater
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so errors from the rest of startup reach the Recent errors screen too.
        runCatching { AppLog.store = ErrorStore(this) }
        keepBackgroundCrashesFromKillingTheApp()
        graph = Graph(this)
        updater = Updater(this, graph.scope)
        // Each startup step runs on its own, so one failing (a bad file, a missing system service)
        // doesn't stop tasks from resuming or the app from opening.
        safely("fix cost units") { graph.settings.fixCostUnits(graph.tasks) }
        safely("move repo settings") { graph.settings.moveGlobalRepoSettings(graph.repos.repos.value) }
        safely("create channels") { graph.notifier.createChannels() }
        safely("resume tasks") { graph.engine.resumeAll() }
        safely("watch network") { graph.watchNetwork(this) }
        safely("watch car") { graph.watchCar(this) }
        safely("watch widget") { graph.watchWidget(this) }
        safely("watch tasks for later") { graph.watchStartLater(this) }
        safely("start sync") { graph.sync.start() }
        safely("refresh pull requests") { graph.engine.refreshPullRequests() }
        safely("start service") { if (graph.hasActiveWork() || graph.engine.isWatchingPullRequests()) TaskService.ensureRunning(this) }
        // Fires at startup and each time the app comes back from the background.
        safely("watch lifecycle") {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = safely("check for updates") { updater.check() }
            })
        }
    }

    /**
     * An uncaught error on a background thread normally kills the whole app, which would also stop every
     * running task. Record it and let only that thread end instead. Errors on the main thread still go to
     * Android, because the UI can't safely keep going after one.
     */
    private fun keepBackgroundCrashesFromKillingTheApp() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            if (thread === Looper.getMainLooper().thread || e is OutOfMemoryError) {
                // Saved before the crash, so it's on the Recent errors screen when the app opens again.
                if (e !is OutOfMemoryError) AppLog.e("App crashed on ${thread.name}", e)
                previous?.uncaughtException(thread, e)
            } else {
                AppLog.e("Uncaught error on ${thread.name}", e)
            }
        }
    }

    private inline fun safely(step: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            AppLog.e("Startup step failed: $step", e)
        }
    }
}

val android.content.Context.graph: Graph get() = (applicationContext as AiAutoApp).graph
