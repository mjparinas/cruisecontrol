package dev.aiauto.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Opens each task's pull request, follows its checks, and merges it when the repo's auto-merge is on.
 * When the repo's auto-fix settings are on, failing checks and merge conflicts go straight back to Claude through [autoFix].
 */
internal class PullRequests(
    private val store: TaskStore,
    private val config: EngineConfig,
    private val journal: TaskJournal,
    private val clock: () -> Long,
    private val githubFactory: (String) -> GitHub,
    private val autoFix: (taskId: String, prompt: String) -> Unit = { _, _ -> },
    private val alert: (taskId: String, kind: AlertKind, text: String) -> Unit = { _, _, _ -> },
) {
    private val mergeRefusals = ConcurrentHashMap<String, String>()

    /** True when [task]'s pull request checks or merge are still worth watching. */
    fun needsWatching(task: Task, now: Long): Boolean {
        if (!task.hasOpenPr) return false
        if (now - (task.prOpenedMillis ?: task.updatedMillis) > WATCH_MILLIS) return false
        val settings = config.repoSettings(task.repo)
        // Someone may merge it by hand at any point, and that merge gets a release.
        if (settings.autoReleaseSince != null) return true
        return when (task.checks?.state) {
            null, Checks.Pending, Checks.None -> true
            Checks.Passing -> settings.autoMerge || settings.autoFixConflicts
            Checks.Failing, Checks.Unknown -> false
        }
    }

    /**
     * True when Claude finished a coding turn but the pull request never opened, most often because GitHub was
     * unreachable at the end of the turn. Kept within [WATCH_MILLIS] of the last update so old finished tasks
     * aren't polled forever.
     */
    fun needsOpening(task: Task, now: Long): Boolean {
        if (task.readOnly || task.prNumber != null) return false
        if (task.status != TaskStatus.Done && task.status != TaskStatus.NeedsInput) return false
        if (config.githubToken() == null) return false
        return now - task.updatedMillis <= WATCH_MILLIS
    }

    /**
     * Opens a pull request for the task's branch if Claude pushed it, or picks up one that already exists.
     * Does nothing when there's no GitHub token or the branch isn't there. Safe to repeat.
     */
    fun open(taskId: String) {
        val task = store.get(taskId) ?: return
        val token = config.githubToken() ?: return
        val github = githubFactory(token)
        if (!github.branchExists(task.repo, task.branch)) return
        val pr = github.findPull(task.repo, task.branch) ?: github.openPull(task.repo, task.branch, task.title, body(task))
        val open = pr.prState == PrState.Open
        val autoMerge = config.repoSettings(task.repo).autoMerge
        // GitHub's own auto-merge keeps working with the phone off. It needs the repo setting
        // and required checks; otherwise the app merges once it sees the checks pass.
        val githubMerges = open && autoMerge && !pr.draft && runCatching { github.enableAutoMerge(pr) }.getOrDefault(false)
        journal.update(taskId) {
            it.copy(
                prUrl = pr.htmlUrl, prNumber = pr.number, prState = pr.prState,
                prOpenedMillis = clock(), githubAutoMerge = githubMerges || pr.hasGitHubAutoMerge,
            )
        }
        journal.status(
            taskId,
            when {
                !open -> "Pull request ${pr.number} is already ${pr.prState.name.lowercase()}."
                githubMerges -> "Pull request ${pr.number} is open. GitHub will merge it when checks pass."
                autoMerge -> "Pull request ${pr.number} is open. I'll merge it when checks pass."
                else -> "Pull request ${pr.number} is open for review."
            },
        )
        if (open && task.prNumber == null) {
            val summary = pr.title.takeIf { it.isNotBlank() } ?: task.title
            journal.status(taskId, "Change summary: $summary. ${pr.changedFiles} files changed, with ${pr.additions} additions and ${pr.deletions} deletions.")
        }
    }

    /**
     * A follow-up turn may have pushed new commits: restart the watch window and read the new head's checks,
     * so a fix for failing checks gets watched and merged instead of staying stuck on the old result.
     */
    fun rewatch(taskId: String) {
        // New commits mean GitHub works out mergeability again; a conflict that's still there gets reported anew.
        journal.update(taskId) { it.copy(prOpenedMillis = clock(), conflicts = false) }
        // On failure the regular refresh tries again later.
        runCatching { refresh(taskId) }
    }

    /** Fetches one task's pull request and checks, says what changed, and merges it if it's ready. */
    fun refresh(taskId: String) {
        val task = store.get(taskId) ?: return
        val number = task.prNumber ?: return
        val token = config.githubToken() ?: return
        val github = githubFactory(token)
        val settings = config.repoSettings(task.repo)
        val pr = github.pull(task.repo, number)
        val state = pr.prState
        val checks = if (state == PrState.Open && pr.head.sha.isNotEmpty()) github.checks(task.repo, pr.head.sha) else task.checks
        // While GitHub is still working out mergeability, keep what it said last.
        val conflicts = state == PrState.Open && (pr.hasConflicts ?: task.conflicts)
        journal.update(taskId) { it.copy(prState = state, checks = checks, githubAutoMerge = pr.hasGitHubAutoMerge, prUrl = pr.htmlUrl, conflicts = conflicts) }

        val willMerge = state == PrState.Open && settings.autoMerge && !pr.draft && !pr.hasGitHubAutoMerge && !conflicts &&
            checks != null && readyToMerge(checks, task, clock())
        val fixingConflicts = conflicts && !task.conflicts && conflictsFound(taskId, number)
        val beforeState = task.prState ?: PrState.Open
        when {
            state == PrState.Merged && beforeState != PrState.Merged -> {
                journal.status(taskId, "Pull request $number was merged.")
                release(taskId, github, pr.mergedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: clock(), pr.mergeCommitSha)
            }
            state == PrState.Closed && beforeState != PrState.Closed -> journal.status(taskId, "Pull request $number was closed without merging.")
            state == PrState.Open && checks != null && checks.state != task.checks?.state -> when (checks.state) {
                Checks.Failing -> {
                    val failed = "Checks failed on pull request $number: ${checks.failing.take(3).joinToString(", ")}."
                    // A conflict fix pushes new commits, which run the checks again, so don't ask for both at once.
                    when {
                        fixingConflicts -> {}
                        tryAutoFix(taskId, settings.autoFixChecks, TaskEngine.fixChecksPrompt(store.get(taskId) ?: task)) ->
                            journal.status(taskId, "$failed Asking Claude to fix them.")
                        else -> {
                            journal.update(taskId) { it.copy(pending = PendingAction.FixChecks) }
                            val question = "$failed Say yes to have Claude fix them."
                            // The alert posts and reads this line itself, so the regular update stays quiet.
                            journal.status(taskId, question, notify = false)
                            alert(taskId, AlertKind.ChecksFailed, question)
                        }
                    }
                }
                Checks.Passing -> journal.status(
                    taskId,
                    "Checks passed on pull request $number." + when {
                        willMerge -> " Merging it now."
                        pr.hasGitHubAutoMerge -> " GitHub will merge it."
                        else -> ""
                    },
                )
                else -> {}
            }
        }
        if (!willMerge) return
        val refusalKey = "$taskId:${pr.head.sha}"
        when (val result = github.merge(task.repo, number)) {
            MergeResult.Merged -> {
                journal.update(taskId) { it.copy(prState = PrState.Merged) }
                journal.status(taskId, "Merged pull request $number.")
                release(taskId, github, clock(), null)
            }
            // Not merged yet: GitHub merges it later, and the next refresh sees that and announces it.
            MergeResult.AutoMergeOn -> {
                journal.update(taskId) { it.copy(githubAutoMerge = true) }
                journal.status(taskId, "GitHub will merge pull request $number when checks pass.")
            }
            is MergeResult.Refused -> if (mergeRefusals.put(refusalKey, result.reason) == null) {
                journal.status(taskId, "Couldn't merge pull request $number. ${result.reason}")
            }
        }
    }

    /**
     * Publishes a release with the next patch version when auto-release is on and the pull request was merged
     * after it was switched on. Each task gets at most one release.
     */
    private fun release(taskId: String, github: GitHub, mergedMillis: Long, mergeSha: String?) {
        val task = store.get(taskId) ?: return
        val since = config.repoSettings(task.repo).autoReleaseSince ?: return
        if (task.releaseTag != null || mergedMillis < since) return
        runCatching {
            github.createRelease(task.repo, Versions.nextPatch(github.tags(task.repo)), mergeSha ?: task.repo.defaultBranch)
        }.onSuccess { tag ->
            journal.update(taskId) { it.copy(releaseTag = tag) }
            journal.status(taskId, "Released $tag.")
        }.onFailure {
            journal.status(taskId, "The release didn't work. ${Failures.describe(it)}")
        }
    }

    /** Merges a task's pull request now, at the user's request. Returns a sentence saying what happened. */
    fun mergeNow(taskId: String): String {
        val task = store.get(taskId) ?: return "That task is gone."
        val number = task.prNumber ?: return "This task has no pull request."
        val token = config.githubToken() ?: return "Add a GitHub token in Settings."
        val message = Projects.mergeMessage(githubFactory(token), task.repo, number)
        journal.status(taskId, message)
        runCatching { refresh(taskId) }
        return message
    }

    /**
     * Announces new merge conflicts and fixes them or asks to. Always returns true once a new conflict has been
     * handled, so a refresh that also sees failing checks doesn't overwrite the conflict question.
     */
    private fun conflictsFound(taskId: String, number: Int): Boolean {
        val task = store.get(taskId) ?: return false
        val base = task.repo.defaultBranch
        if (tryAutoFix(taskId, config.repoSettings(task.repo).autoFixConflicts, TaskEngine.fixConflictsPrompt(task))) {
            journal.status(taskId, "Pull request $number has merge conflicts with $base. Asking Claude to fix them.")
            return true
        }
        journal.update(taskId) { it.copy(pending = PendingAction.FixConflicts) }
        journal.status(taskId, "Pull request $number has merge conflicts with $base. Say yes to have Claude fix them.")
        return true
    }

    /** Hands [prompt] to Claude when [enabled] and the task hasn't used up its automatic fixes. */
    private fun tryAutoFix(taskId: String, enabled: Boolean, prompt: String): Boolean {
        if (!enabled || (store.get(taskId)?.autoFixes ?: MAX_AUTO_FIXES) >= MAX_AUTO_FIXES) return false
        journal.update(taskId) { it.copy(autoFixes = it.autoFixes + 1, pending = null) }
        autoFix(taskId, prompt)
        return true
    }

    private fun readyToMerge(checks: ChecksSummary, task: Task, now: Long): Boolean = when (checks.state) {
        Checks.Passing -> true
        // A repo without CI: give checks time to show up before treating "none" as green.
        Checks.None -> now - (task.prOpenedMillis ?: task.updatedMillis) > NO_CHECKS_GRACE_MILLIS
        else -> false
    }

    private fun body(task: Task): String = buildString {
        appendLine("Requested by voice from Cruise Control.")
        appendLine()
        task.messages.filter { it.speaker == Speaker.You }.forEach { appendLine("> ${it.text}") }
        task.messages.lastOrNull { it.speaker == Speaker.Claude }?.let {
            appendLine()
            appendLine(it.text)
        }
    }

    internal companion object {
        /** How long after opening a pull request the app keeps watching its checks. */
        const val WATCH_MILLIS = 3 * 60 * 60 * 1000L
        const val NO_CHECKS_GRACE_MILLIS = 10 * 60 * 1000L
        /** Automatic fix attempts per task before the app goes back to asking the driver. */
        const val MAX_AUTO_FIXES = 3
    }
}
