package dev.aiauto.core

/** What a pull request merge would do, worked out before the driver confirms it. */
data class MergePreview(
    val repo: RepoRef,
    val number: Int,
    /** True when the pull request is open, not a draft, has no conflicts, and checks aren't failing or unknown. */
    val ready: Boolean,
    /** One or two spoken sentences on where the pull request stands. */
    val summary: String,
)

/**
 * The status, stop and merge side of the car's voice assistant. Works out which repo, task or pull request the
 * driver means from loosely spoken names, then asks the engine or GitHub. Blocking: call it off the main thread.
 */
class VoiceControls(
    private val store: TaskStore,
    private val repos: () -> List<RepoRef>,
    private val engine: TaskEngine,
    private val github: () -> GitHub?,
    /** Merges a pull request, through its task when the app started it. Returns a sentence saying what happened. */
    private val merge: (RepoRef, Int) -> String,
) {
    /**
     * Where things stand. With a task, that task; with a pull request number, that pull request and its checks;
     * with only a repo, the repo's main branch and open pull requests; with nothing, the tasks that are still going.
     */
    fun status(repo: String, prNumber: Int?, taskId: String): String {
        findTask(taskId)?.let { task ->
            if (task.hasOpenPr && !engine.isRunning(task.id)) runCatching { engine.refreshPullRequest(task.id) }
            return "${task.title}, in ${task.repo.name}. ${engine.statusOf(task.id)}"
        }
        if (prNumber != null) {
            val target = resolveRepo(repo, prNumber)
            val gh = requireGitHub()
            val pull = gh.pull(target, prNumber)
            val checks = if (pull.prState == PrState.Open && pull.head.sha.isNotEmpty()) gh.checks(target, pull.head.sha) else null
            return SpokenStatus.forPull(pull, checks)
        }
        if (repo.isNotBlank()) return SpokenStatus.forRepo(requireGitHub().status(resolveRepo(repo, null)))
        val going = store.all().filter { !it.archived && !it.isFinished }.needsYouFirst()
        if (going.isEmpty()) return "Nothing is running, and no task is waiting on you."
        return going.take(MAX_LISTED).joinToString(" ") { "${it.title}, in ${it.repo.name}: ${engine.statusOf(it.id)}" } +
            if (going.size > MAX_LISTED) " And ${going.size - MAX_LISTED} more." else ""
    }

    /** Stops [taskId], or the only task that's working when no id is given. */
    fun stop(taskId: String): String {
        val task = findTask(taskId) ?: run {
            if (taskId.isNotBlank()) throw IllegalArgumentException("No task with id $taskId.")
            val working = store.all().filter { engine.isRunning(it.id) || it.isActive }
            when (working.size) {
                0 -> return "Nothing is running right now."
                1 -> working.first()
                else -> throw IllegalArgumentException(
                    "More than one task is running: ${working.joinToString("; ") { "${it.id} (${it.title}, in ${it.repo.name})" }}. Ask which one.",
                )
            }
        }
        if (!engine.isRunning(task.id) && !task.isActive) return "${task.title} isn't running."
        engine.stop(task.id)
        return "Stopped ${task.title}, in ${task.repo.name}."
    }

    /** Looks up pull request [prNumber] and says whether it can merge, without merging it. */
    fun previewMerge(repo: String, prNumber: Int?): MergePreview {
        val number = prNumber ?: onlyOpenPull(repo)
        val target = resolveRepo(repo, number)
        val gh = requireGitHub()
        val pull = gh.pull(target, number)
        val checks = if (pull.prState == PrState.Open && pull.head.sha.isNotEmpty()) gh.checks(target, pull.head.sha) else null
        // Match PullRequests: drafts never merge, Unknown is not green, and None needs the same care as auto-merge.
        val checksOk = when (checks?.state) {
            Checks.Passing, Checks.Pending, Checks.None -> true
            else -> false
        }
        val ready = pull.prState == PrState.Open && !pull.draft && pull.hasConflicts != true && checksOk
        val extra = when {
            !ready -> ""
            checks?.state == Checks.Pending -> " Checks are still running, so merging turns on auto-merge and GitHub merges it once they pass."
            else -> " It can merge into ${target.defaultBranch}."
        }
        return MergePreview(target, number, ready, SpokenStatus.forPull(pull, checks) + extra)
    }

    fun merge(repo: RepoRef, number: Int): String = merge.invoke(repo, number)

    private fun findTask(taskId: String): Task? = taskId.trim().takeIf { it.isNotEmpty() }?.let { store.get(it) }

    /** The open pull request the driver most likely means when they didn't say a number. */
    private fun onlyOpenPull(repo: String): Int {
        val saved = if (repo.isBlank()) null else resolveRepo(repo, null)
        val open = store.all().filter { it.hasOpenPr && !it.archived && (saved == null || it.repo.fullName == saved.fullName) }
        return open.singleOrNull()?.prNumber ?: throw IllegalArgumentException(
            if (open.isEmpty()) "Which pull request? Say its number." else "Which pull request? Open ones: ${open.joinToString { "${it.prNumber} in ${it.repo.name}" }}.",
        )
    }

    /** Finds the saved repo the driver named, or the only one that fits when they didn't name one. */
    private fun resolveRepo(spoken: String, prNumber: Int?): RepoRef {
        val saved = repos()
        if (spoken.isNotBlank()) {
            saved.firstOrNull { it.fullName.equals(spoken.trim(), ignoreCase = true) }?.let { return it }
            val matches = RepoRef.matchSpoken(spoken, saved)
            return matches.singleOrNull() ?: throw IllegalArgumentException(
                if (matches.isEmpty()) "No saved repo matches \"$spoken\". Saved: ${saved.joinToString { it.fullName }}."
                else "More than one repo matches: ${matches.joinToString { it.fullName }}.",
            )
        }
        if (prNumber != null) {
            // Prefer open, non-archived tasks: the same number can exist in several repos.
            val owners = store.all()
                .filter { it.prNumber == prNumber }
                .sortedWith(compareBy<Task> { it.archived }.thenByDescending { it.hasOpenPr })
                .map { it.repo }
                .distinctBy { it.fullName }
            when (owners.size) {
                1 -> return owners.first()
                else -> if (owners.isNotEmpty()) throw IllegalArgumentException(
                    "Pull request $prNumber is in more than one repo: ${owners.joinToString { it.fullName }}. Say which one.",
                )
            }
        }
        return saved.singleOrNull() ?: throw IllegalArgumentException(
            if (saved.isEmpty()) "No repos are saved yet." else "Which repo? Saved: ${saved.joinToString { it.name }}.",
        )
    }

    private fun requireGitHub(): GitHub = github() ?: throw IllegalStateException("Add a GitHub token in the app on your phone first.")

    private companion object {
        const val MAX_LISTED = 4
    }
}
