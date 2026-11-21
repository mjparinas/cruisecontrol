package dev.aiauto.data

import android.util.Log
import dev.aiauto.core.Failures
import dev.aiauto.core.GitHub
import dev.aiauto.core.GitHubException
import dev.aiauto.core.RepoSync
import dev.aiauto.core.SyncRepoNotPrivate
import dev.aiauto.core.SyncSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Syncs repos, tasks, settings and the car chat's memory through a private repo on the user's GitHub account.
 * Runs every few minutes while the app is alive, shortly after tasks change, and when asked from Settings.
 */
class SyncManager(
    private val settings: Settings,
    private val secrets: Secrets,
    private val tasks: FileTaskStore,
    private val repos: RepoStore,
    private val chat: FileChatMemory,
    private val scope: CoroutineScope,
    /** Clears a task that was cleared on another device: stops it if it runs here, and removes its notification. */
    private val removeTask: (String) -> Unit = tasks::remove,
) {
    private val lock = Mutex()
    @Volatile private var checkedGist = false

    /** Starts the background rounds. Each one does nothing while sync is off. */
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            while (true) {
                syncQuietly()
                delay(INTERVAL_MILLIS)
            }
        }
        scope.launch {
            tasks.tasks.drop(1).debounce(AFTER_CHANGE_MILLIS).collect { syncQuietly() }
        }
    }

    fun syncSoon() {
        scope.launch { syncQuietly() }
    }

    private suspend fun syncQuietly() {
        if (!settings.syncEnabled) return
        runCatching { syncNow() }.onFailure { Log.w("AiAuto", "Sync failed", it) }
    }

    /** One sync round. Returns a sentence for Settings saying how it went. */
    suspend fun syncNow(): String = lock.withLock {
        val token = secrets.githubToken ?: return "Add a GitHub token first."
        withContext(Dispatchers.IO) {
            try {
                round(token)
                settings.lastSyncMillis = System.currentTimeMillis()
                settings.lastSyncError = null
                "Synced."
            } catch (e: Exception) {
                val message = describe(e)
                settings.lastSyncError = message
                message
            }
        }
    }

    private fun round(token: String) {
        val me = settings.deviceId
        // Tasks from before sync have no device. Mark them as this one's, so other devices don't run them too.
        tasks.all().filter { it.device == null }.forEach { tasks.put(it.copy(device = me)) }
        val local = SyncSnapshot(
            repos = repos.repos.value,
            tasks = tasks.all(),
            settings = settings.exportSynced(),
            settingsChangedMillis = settings.settingsChangedMillis,
            chat = if (settings.rememberChats) chat.recent() else emptyList(),
            removedTasks = tasks.removedIds(),
            chatForgottenMillis = chat.forgottenMillis,
            repoChanges = repos.changes(),
        )
        // The first round since the app started also moves an old sync gist into the repo and deletes it, in case a
        // device still on an older version made one again.
        val result = RepoSync(GitHub(token)).sync(local, checkGist = !checkedGist)
        checkedGist = true
        if (result.movedGist) settings.syncGistId = null
        val merged = result.merged

        // Tasks cleared on another device go here too, instead of coming back on the next round.
        tasks.rememberRemoved(merged.removedTasks)
        merged.removedTasks.filter { tasks.get(it) != null }.forEach(removeTask)
        merged.tasks.forEach { incoming ->
            val current = tasks.get(incoming.id)
            // Skip a task that changed here while the round was running; the next round sends it.
            if (current == null || (current != incoming && current.updatedMillis <= incoming.updatedMillis)) tasks.put(incoming)
        }
        repos.applySynced(merged.repos, merged.repoChanges)
        if (merged.settingsChangedMillis > local.settingsChangedMillis) settings.importSynced(merged.settings, merged.settingsChangedMillis)
        if (settings.rememberChats) chat.replaceAll(merged.chat, merged.chatForgottenMillis)
    }

    private fun describe(e: Exception): String = when {
        e is SyncRepoNotPrivate -> "${e.repo} is public, so sync is paused. Make it private on GitHub, then sync again."
        e is GitHubException && (e.code == 403 || e.code == 404) ->
            "Your GitHub token can't make or use the private ${RepoSync.REPO} repo. Make that repo on GitHub as private, " +
                "and give the token Contents read and write on it."
        e is GitHubException && e.code == 401 -> "GitHub didn't accept the token. Save a new one above."
        else -> Failures.describe(e)
    }

    private companion object {
        const val INTERVAL_MILLIS = 3 * 60_000L
        const val AFTER_CHANGE_MILLIS = 20_000L
    }
}
