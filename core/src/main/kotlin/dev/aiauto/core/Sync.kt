package dev.aiauto.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything that syncs between devices. Keys and tokens never sync: each device keeps its own, encrypted.
 * [settings] holds plain settings as strings, so new ones sync without a format change.
 */
@Serializable
data class SyncSnapshot(
    val version: Int = 1,
    val repos: List<RepoRef> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
    /** When [settings] last changed on any device. The newer side wins as a whole. */
    val settingsChangedMillis: Long = 0,
    val chat: List<ChatExchange> = emptyList(),
    /** Ids of tasks cleared on any device, so a merge doesn't bring them back from the other side. */
    val removedTasks: List<String> = emptyList(),
    /** When the car chat's memory was last cleared on any device. Exchanges from before then stay forgotten. */
    val chatForgottenMillis: Long = 0,
    /** When each repo was last added and removed on any device, so a removal syncs and a later add wins over it. */
    val repoChanges: List<RepoChange> = emptyList(),
)

/** When a repo was last added and last removed. Times are 0 when that never happened, or happened before sync knew. */
@Serializable
data class RepoChange(val fullName: String, val addedMillis: Long = 0, val removedMillis: Long = 0) {
    val removed: Boolean get() = removedMillis > addedMillis
}

object SyncMerge {
    /** Messages kept per task in the synced copy, so the gist stays small. Each device keeps its own full history. */
    const val MESSAGES_PER_TASK = 60

    /** Cleared task ids kept as tombstones. Old ones drop off; by then every device has seen them. */
    const val REMOVED_KEPT = 300

    /**
     * Combines two snapshots. Repos are a union, minus any removed later than it was added. Each task comes from whichever side changed it last, keeping every
     * message either side has. Settings come from whichever side changed them last. The car chat keeps the newest
     * exchanges from both. A task cleared on either side stays cleared, and so does car chat forgotten on either side.
     */
    fun merge(local: SyncSnapshot, remote: SyncSnapshot): SyncSnapshot {
        val (repos, repoChanges) = mergeRepos(local.repos, local.repoChanges, remote.repos, remote.repoChanges)
        val removed = (remote.removedTasks + local.removedTasks).distinct().takeLast(REMOVED_KEPT)
        val removedSet = removed.toSet()
        val remoteTasks = remote.tasks.associateBy { it.id }
        val ids = (local.tasks.map { it.id } + remote.tasks.map { it.id }).distinct().filter { it !in removedSet }
        val localTasks = local.tasks.associateBy { it.id }
        val tasks = ids.map { id ->
            val a = localTasks[id]
            val b = remoteTasks[id]
            when {
                a == null -> b!!
                b == null -> a
                else -> {
                    val newer = if (b.updatedMillis > a.updatedMillis) b else a
                    newer.copy(
                        messages = (a.messages + b.messages).distinctBy { it.id }.sortedBy { it.timeMillis },
                        // Unsent prompts never sync, so keep this device's own unless another device took the task over.
                        pendingPrompt = if (newer.device == a.device) a.pendingPrompt else newer.pendingPrompt,
                    )
                }
            }
        }.sortedByDescending { it.updatedMillis }
        val remoteSettingsWin = remote.settingsChangedMillis > local.settingsChangedMillis
        val forgotten = maxOf(local.chatForgottenMillis, remote.chatForgottenMillis)
        val chat = (local.chat + remote.chat)
            .filter { it.timeMillis > forgotten }
            .distinctBy { it.timeMillis to it.said }
            .sortedBy { it.timeMillis }
            .takeLast(ChatMemory.KEEP)
        return SyncSnapshot(
            repos = repos,
            tasks = tasks,
            settings = if (remoteSettingsWin) remote.settings else local.settings,
            settingsChangedMillis = maxOf(local.settingsChangedMillis, remote.settingsChangedMillis),
            chat = chat,
            removedTasks = removed,
            chatForgottenMillis = forgotten,
            repoChanges = repoChanges,
        )
    }

    /**
     * Repos from both sides, in order, without any whose latest change on either side is a removal. Keeps the newest
     * add and removal time for each repo, so a repo removed on one device and added again later on another comes back.
     */
    fun mergeRepos(
        local: List<RepoRef>,
        localChanges: List<RepoChange>,
        remote: List<RepoRef>,
        remoteChanges: List<RepoChange>,
    ): Pair<List<RepoRef>, List<RepoChange>> {
        val changes = (localChanges + remoteChanges).groupBy { it.fullName.lowercase() }.map { (_, same) ->
            RepoChange(same.first().fullName, same.maxOf { it.addedMillis }, same.maxOf { it.removedMillis })
        }
        val removed = changes.filter { it.removed }.map { it.fullName.lowercase() }.toSet()
        val repos = (local + remote).distinctBy { it.fullName.lowercase() }.filter { it.fullName.lowercase() !in removed }
        return repos to changes
    }

    /** The copy that goes in the gist: recent messages only, and nothing waiting to be sent. */
    fun forUpload(snapshot: SyncSnapshot): SyncSnapshot = snapshot.copy(
        tasks = snapshot.tasks.map { it.copy(messages = it.messages.takeLast(MESSAGES_PER_TASK), pendingPrompt = null) },
    )
}

/** The sync repo exists but anyone can see it, so the app won't write tasks and chats there. */
class SyncRepoNotPrivate(val repo: String) : IllegalStateException("$repo is public, so sync won't use it. Make it private on GitHub.")

/**
 * Keeps a [SyncSnapshot] in one file of a private repo on the user's GitHub account, so every device signed in with
 * the same account shares it and nobody else can read it. The app makes the repo on first use. The token needs
 * Contents set to read and write on that repo, and Administration set to read and write to make it.
 *
 * Sync used to live in a secret gist, which anyone with its link can read. [sync] moves a gist it finds into the repo,
 * then deletes the gist.
 */
class RepoSync(private val github: GitHub, private val repoName: String = REPO) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /** How a round went: the merged snapshot to apply locally, and whether an old gist was moved over and deleted. */
    data class Result(val merged: SyncSnapshot, val movedGist: Boolean)

    /**
     * One round: reads the sync file (making the repo when there is none), merges it with [local], and writes the
     * result back when it differs. With [checkGist], also folds in and deletes an old sync gist. Blocking.
     */
    fun sync(local: SyncSnapshot, checkGist: Boolean = false): Result {
        val repo = ensureRepo()
        val gist = if (checkGist) LegacyGist(github).takeOver() else null
        val start = gist?.second?.let { SyncMerge.merge(local, it) } ?: local
        val merged = try {
            round(repo, start)
        } catch (e: GitHubException) {
            // Another device wrote the file between our read and write. Read it again and merge once more.
            if (e.code != 409 && e.code != 422) throw e
            round(repo, start)
        }
        // Only delete the gist once its contents are safely in the repo.
        gist?.let { (id, _) -> runCatching { github.deleteGist(id) } }
        return Result(merged, gist != null)
    }

    private fun round(repo: RepoRef, local: SyncSnapshot): SyncSnapshot {
        val file = github.repoFile(repo, FILE)
        if (file == null) {
            // Marked as changed, so a device that joins later with untouched defaults takes these settings.
            val first = local.copy(settingsChangedMillis = maxOf(1L, local.settingsChangedMillis))
            github.putRepoFile(repo, FILE, encode(SyncMerge.forUpload(first)), sha = null, message = COMMIT_MESSAGE)
            return first
        }
        val remote = file.content.takeIf { it.isNotBlank() }?.let { json.decodeFromString(SyncSnapshot.serializer(), it) }
        val merged = if (remote == null) local else SyncMerge.merge(local, remote)
        val upload = SyncMerge.forUpload(merged)
        if (remote == null || upload != SyncMerge.forUpload(remote)) {
            github.putRepoFile(repo, FILE, encode(upload), sha = file.sha, message = COMMIT_MESSAGE)
        }
        return merged
    }

    private fun ensureRepo(): RepoRef {
        val login = github.myLogin()
        val existing = github.repoVisibility(login, repoName)
        if (existing != null) {
            if (!existing.second) throw SyncRepoNotPrivate("$login/$repoName")
            return existing.first
        }
        return github.createPrivateRepo(repoName, DESCRIPTION)
    }

    private fun encode(snapshot: SyncSnapshot) = json.encodeToString(SyncSnapshot.serializer(), snapshot)

    companion object {
        const val REPO = "cruise-control-sync"
        const val FILE = "cruise-control-sync.json"
        const val DESCRIPTION = "Cruise Control sync data (repos, tasks and settings). Keys and tokens are not stored here."
        const val COMMIT_MESSAGE = "Sync"
    }
}

/** The old sync gist, read once so its contents can move to the private repo. */
internal class LegacyGist(private val github: GitHub) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The old gist's id and contents, or null when there is none or the token can't read gists. */
    fun takeOver(): Pair<String, SyncSnapshot?>? = runCatching {
        val id = github.myGists().firstOrNull { RepoSync.FILE in it.files }?.id ?: return null
        val snapshot = github.gistFile(id, RepoSync.FILE)?.takeIf { it.isNotBlank() }
            ?.let { json.decodeFromString(SyncSnapshot.serializer(), it) }
        id to snapshot
    }.getOrNull()
}
