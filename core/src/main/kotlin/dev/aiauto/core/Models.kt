package dev.aiauto.core

import kotlinx.serialization.Serializable

/** A GitHub repository the user registered in the app. */
@Serializable
data class RepoRef(
    val owner: String,
    val name: String,
    val defaultBranch: String = "main",
) {
    val fullName: String get() = "$owner/$name"
    val url: String get() = "https://github.com/$owner/$name"

    companion object {
        private val pattern = Regex("""^(?:https?://github\.com/)?([\w.-]+)/([\w.-]+?)(?:\.git)?/?$""")

        /** Accepts `owner/name` or a github.com URL. Returns null when the text isn't a repo. */
        fun parse(text: String): RepoRef? {
            val match = pattern.matchEntire(text.trim()) ?: return null
            // "." and ".." would turn the API path into a different GitHub endpoint.
            if (match.groupValues.drop(1).any { it.all { c -> c == '.' } }) return null
            return RepoRef(match.groupValues[1], match.groupValues[2])
        }

        /**
         * Finds the repos in [candidates] that match a spoken name, ignoring case, spaces and punctuation,
         * so "AI auto" matches aiauto and "my parinas slash ai auto" can match mjparinas/aiauto.
         * Matches on the full name first, then on the name alone.
         */
        fun matchSpoken(spoken: String, candidates: List<RepoRef>): List<RepoRef> {
            fun norm(s: String) = s.lowercase().replace(" slash ", "/").filter { it.isLetterOrDigit() }
            val said = norm(spoken)
            if (said.isEmpty()) return emptyList()
            val byFull = candidates.filter { norm(it.fullName) == said }
            if (byFull.isNotEmpty()) return byFull
            val nameOnly = norm(spoken.substringAfterLast('/').substringAfterLast(" slash "))
            return candidates.filter { norm(it.name) == nameOnly }
        }
    }
}

@Serializable
enum class TaskStatus { Starting, Working, NeedsInput, Done, Failed, Stopped }

/** Where a pull request stands on GitHub. */
@Serializable
enum class PrState { Open, Merged, Closed }

/**
 * The combined result of a commit's checks (GitHub Actions and other status checks).
 * [Unknown] means the token can't read them, which is never treated as passing.
 */
@Serializable
enum class Checks { None, Pending, Passing, Failing, Unknown }

@Serializable
data class ChecksSummary(
    val state: Checks,
    val failing: List<String> = emptyList(),
    val total: Int = 0,
)

/** Something worth interrupting the driver for, even in quiet mode. */
@Serializable
enum class AlertKind {
    /** Checks failed on the task's pull request and Claude isn't already fixing them. */
    ChecksFailed,
    /** Claude finished its turn with a question for the driver. */
    Question,
    /** Claude has been working without a single update for a long time. */
    Stuck,
}

/**
 * A step Claude's safety check flagged for review, held while the car is parked so the driver can approve or deny it
 * on the car screen. While driving such steps are declined instead.
 */
@Serializable
data class ToolApproval(
    val toolUseId: String,
    val toolName: String,
    /** The command, file or address the step would touch, when known. */
    val detail: String? = null,
) {
    /** What the step does, for the car screen and for reading aloud. */
    val summary: String get() = if (detail.isNullOrBlank()) "run a ${toolName} step" else "run ${toolName}: ${detail}"
}

/** Something the app asked the driver, waiting for a spoken yes or no. */
@Serializable
enum class PendingAction { Merge, FixChecks, FixConflicts, StartOver }

@Serializable
enum class Speaker { You, Claude, Status }

/** One line in a task's conversation, shown on the phone and read aloud in the car. */
@Serializable
data class TaskMessage(
    val id: String,
    val speaker: Speaker,
    val text: String,
    val timeMillis: Long,
)

/** A unit of work: one Managed Agents session working on one repo. */
@Serializable
data class Task(
    val id: String,
    val repo: RepoRef,
    val title: String,
    val branch: String,
    val sessionId: String? = null,
    val status: TaskStatus = TaskStatus.Starting,
    val prUrl: String? = null,
    val prNumber: Int? = null,
    /** The release the app published after this task's pull request was merged. */
    val releaseTag: String? = null,
    val messages: List<TaskMessage> = emptyList(),
    val createdMillis: Long,
    val updatedMillis: Long,
    /** A prompt that hasn't reached Claude yet. It's sent again when the task resumes. */
    val pendingPrompt: String? = null,
    /** Stopped because the connection dropped, not because of Claude. Resumes when the network is back. */
    val interrupted: Boolean = false,
    /** When the connection first dropped in this run of trouble. Cleared once a turn finishes or the task stops for good. */
    val interruptedSinceMillis: Long? = null,
    /** Live pull request state, refreshed from GitHub. Null until a pull request exists. */
    val prState: PrState? = null,
    val checks: ChecksSummary? = null,
    /** GitHub's own auto-merge is on for the pull request, so GitHub merges it once required checks pass. */
    val githubAutoMerge: Boolean = false,
    val prOpenedMillis: Long? = null,
    /** What this task's Claude session has cost so far, in US dollars at list price. */
    val costUsd: Double = 0.0,
    /** Put away by the user: shown on the Archived tab instead of the main list. */
    val archived: Boolean = false,
    /** A yes-or-no question the app asked, answered by the next reply. */
    val pending: PendingAction? = null,
    /** GitHub says the pull request can't merge cleanly into the base branch. */
    val conflicts: Boolean = false,
    /** Fixes the app asked Claude for on its own since the driver last spoke, so it can't loop forever. */
    val autoFixes: Int = 0,
    /** A flagged step waiting for the driver to approve or deny it while parked. */
    val approval: ToolApproval? = null,
    /**
     * The device that runs this task, when tasks sync between devices. Only that device follows the session and
     * watches the pull request; another device takes it over when you reply or retry from it. Null means this device.
     */
    val device: String? = null,
) {
    /** A pull request exists and hasn't been merged or closed yet. */
    val hasOpenPr: Boolean get() = prNumber != null && (prState ?: PrState.Open) == PrState.Open

    /** Claude finished, but the pull request's checks are still running or haven't been read yet. */
    val checksRunning: Boolean
        get() = status == TaskStatus.Done && hasOpenPr && (checks == null || checks.state == Checks.Pending)

    /** Mid-turn, or cut off by a lost connection and still resumable. */
    val isActive: Boolean
        get() = status == TaskStatus.Working || status == TaskStatus.Starting || interrupted

    /** Nothing left to do or watch: safe to clear from the list. */
    val isFinished: Boolean
        get() = status == TaskStatus.Stopped ||
            (status == TaskStatus.Failed && !interrupted) ||
            (status == TaskStatus.Done && !hasOpenPr)

    val lastMessage: TaskMessage? get() = messages.lastOrNull()
}

/** Tasks waiting on the user's reply come first, so they're never pushed off a short list. Otherwise keeps the order. */
fun List<Task>.needsYouFirst(): List<Task> = sortedBy { it.status != TaskStatus.NeedsInput }

/**
 * IDs of the agent and environments created for the current API key. [profile] is what the agent was made with;
 * null for setups saved before the app had agent settings. [webEnvironmentId] is the environment with open internet
 * access, made the first time web search is switched on.
 */
@Serializable
data class AgentSetup(
    val agentId: String,
    val agentVersion: Int,
    val environmentId: String,
    val profile: AgentProfile? = null,
    val webEnvironmentId: String? = null,
) {
    /** The environment a new session should run in. */
    val sessionEnvironmentId: String get() = if (profile?.webSearch == true) webEnvironmentId ?: environmentId else environmentId
}
