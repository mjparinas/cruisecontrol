package dev.aiauto.core

import kotlinx.serialization.Serializable

/**
 * Settings that belong to one repo, set by tapping it in Settings. Global settings cover what's the same everywhere
 * (the model, standing rules, voice, spending); these cover what depends on the repo: what happens to its pull
 * requests, and rules Claude follows on every task in it. A repo that was never changed uses the defaults here.
 */
@Serializable
data class RepoSettings(
    /** Merge a task's pull request once its checks pass. */
    val autoMerge: Boolean = true,
    /** Send failing checks on a task's pull request back to Claude without asking. */
    val autoFixChecks: Boolean = true,
    /** Have Claude resolve merge conflicts on a task's pull request without asking. */
    val autoFixConflicts: Boolean = true,
    /** When auto-release was switched on, or null when it's off. Only pull requests merged after this get a release. */
    val autoReleaseSince: Long? = null,
    /** Rules Claude follows on every task in this repo, one per line, like "Run npm test before pushing". */
    val instructions: String = "",
) {
    /** The non-blank lines of [instructions]. */
    val instructionLines: List<String> get() = instructions.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** A short line for the repo's row in Settings, saying what the app does on its own there. */
    fun summary(): String {
        val fixes = listOfNotNull("conflicts".takeIf { autoFixConflicts }, "failing checks".takeIf { autoFixChecks })
        val rules = instructionLines.size
        val parts = listOfNotNull(
            if (autoMerge) "Merges when checks pass" else "Waits for you to merge",
            if (fixes.isEmpty()) null else "fixes " + fixes.joinToString(" and "),
            "releases on merge".takeIf { autoReleaseSince != null },
            if (rules == 0) null else "$rules " + (if (rules == 1) "rule" else "rules") + " for Claude",
        )
        return parts.joinToString(", ") + "."
    }

    /** Turns auto-release on or off, keeping when it was first turned on so earlier merges still don't get a release. */
    fun withAutoRelease(on: Boolean, now: Long): RepoSettings =
        copy(autoReleaseSince = if (on) autoReleaseSince ?: now else null)

    companion object {
        /** Nothing automatic. What the engine uses when the app doesn't say otherwise. */
        val MANUAL = RepoSettings(autoMerge = false, autoFixChecks = false, autoFixConflicts = false)

        /** [repo]'s settings in [saved], keyed by full name in any case, or the defaults when it has none. */
        fun of(saved: Map<String, RepoSettings>, repo: RepoRef): RepoSettings =
            saved[repo.fullName] ?: saved.entries.firstOrNull { it.key.equals(repo.fullName, ignoreCase = true) }?.value ?: RepoSettings()

        /**
         * Gives every repo in [repos] that has no saved settings a copy of [legacy], for moving the pull request
         * switches that used to be global onto each repo. Repos that already have settings keep them.
         */
        fun seed(saved: Map<String, RepoSettings>, repos: List<RepoRef>, legacy: RepoSettings): Map<String, RepoSettings> =
            saved + repos.filter { repo -> saved.keys.none { it.equals(repo.fullName, ignoreCase = true) } }.associate { it.fullName to legacy }
    }
}
