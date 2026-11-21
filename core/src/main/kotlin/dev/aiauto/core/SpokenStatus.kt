package dev.aiauto.core

/**
 * Turns agent activity into short lines that are safe to show and read aloud while driving:
 * one sentence, no code, no markdown.
 */
object SpokenStatus {
    private const val MAX_CHARS = 220
    private const val GLANCE_CHARS = 120

    fun forToolUse(toolName: String, command: String?): String? = when (toolName) {
        "bash" -> command?.let { describeCommand(it) } ?: "Running a command"
        "read" -> "Reading files"
        "write", "edit" -> "Editing files"
        "glob", "grep" -> "Searching the code"
        "web_search", "web_fetch" -> "Looking something up"
        else -> null
    }

    private fun describeCommand(command: String): String {
        val c = command.trim()
        return when {
            c.startsWith("git push") -> "Pushing changes to GitHub"
            c.startsWith("git commit") -> "Committing changes"
            c.startsWith("git checkout") || c.startsWith("git switch") -> "Creating a branch"
            c.startsWith("git") -> "Working with git"
            Regex("""\b(test|pytest|jest|gradle|npm|pnpm|yarn|cargo|go)\b""").containsMatchIn(c) -> "Building or testing"
            else -> "Running a command"
        }
    }

    /** Strips markdown and trims an agent message to something speakable. */
    fun clean(text: String): String {
        var t = text
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
            .replace(Regex("""(^|\n)\s*#{1,6}\s*"""), "$1")
            .replace(Regex("""\*\*|__|\*"""), "")
            .replace(Regex("""(^|\n)\s*[-•]\s+"""), "$1")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (t.length > MAX_CHARS) {
            val cut = t.lastIndexOf(". ", MAX_CHARS)
            t = if (cut > 60) t.substring(0, cut + 1) else t.substring(0, MAX_CHARS).trimEnd() + "…"
        }
        return t
    }

    /**
     * A line short enough to take in at a glance on the car screen: whole sentences up to [max] characters, or the
     * first [max] cut at a word with "…". The full text is still read aloud.
     */
    fun glance(text: String, max: Int = GLANCE_CHARS): String {
        val t = text.trim()
        if (t.length <= max) return t
        val sentence = Regex("""[.!?](\s|$)""").findAll(t).map { it.range.first + 1 }.lastOrNull { it <= max }
        if (sentence != null && sentence >= max / 3) return t.substring(0, sentence)
        val space = t.lastIndexOf(' ', max - 1)
        return (if (space >= max / 2) t.substring(0, space) else t.substring(0, max - 1)).trimEnd(' ', ',', ';', ':') + "…"
    }

    /** "checks passing", "2 checks failing", … for a row or a sentence. */
    fun checks(summary: ChecksSummary?): String = when (summary?.state) {
        null -> "checks not loaded"
        Checks.None -> "no checks"
        Checks.Pending -> "checks running"
        Checks.Passing -> "checks passing"
        Checks.Failing -> if (summary.failing.size == 1) "${summary.failing[0]} failing" else "${summary.failing.size} checks failing"
        Checks.Unknown -> "checks hidden from your token"
    }

    /** The pull request part of a task's status, e.g. "PR 5 merged" or "PR 5, checks running". */
    fun pullRequest(task: Task): String? {
        val number = task.prNumber ?: return null
        return when (task.prState) {
            PrState.Merged -> "PR $number merged"
            PrState.Closed -> "PR $number closed"
            else -> "PR $number, ${checks(task.checks)}"
        }
    }

    fun money(usd: Double): String = when {
        usd <= 0.0 -> "$0"
        usd < 0.01 -> "under 1¢"
        else -> "$" + "%.2f".format(java.util.Locale.US, usd)
    }

    /** A spoken summary of a repo for the car: main branch, last change, and open pull requests. */
    fun forRepo(status: RepoStatus, nowMillis: Long = System.currentTimeMillis()): String = buildString {
        append(status.repo.name).append(". ")
        val latest = status.latest
        if (latest == null) {
            append("${status.repo.defaultBranch} has no commits yet. ")
        } else {
            append("${status.repo.defaultBranch.replaceFirstChar { it.uppercase() }} has ${checks(status.branchChecks)}. ")
            append("Last change: ${clean(latest.message)}")
            latest.timeMillis?.let { append(", ${ago(nowMillis - it)}") }
            append(". ")
        }
        when (status.pulls.size) {
            0 -> append("No open pull requests.")
            1 -> append("One open pull request. ")
            else -> append("${status.pulls.size} open pull requests. ")
        }
        status.pulls.take(4).forEach { p ->
            append("Number ${p.pull.number}, ${clean(p.pull.title)}, ${if (p.pull.draft) "draft, " else ""}${checks(p.checks)}. ")
        }
    }.trim()

    /** A spoken summary of one pull request. */
    fun forPull(pull: PullRequest, checks: ChecksSummary?): String = buildString {
        append("Pull request ${pull.number}, ${clean(pull.title)}. ")
        when (pull.prState) {
            PrState.Merged -> append("It's merged.")
            PrState.Closed -> append("It's closed.")
            PrState.Open -> {
                append(checks(checks).replaceFirstChar { it.uppercase() }).append(". ")
                if (checks?.state == Checks.Failing && checks.failing.size > 1) append("Failing: ${checks.failing.take(3).joinToString(", ")}. ")
                if (pull.draft) append("It's a draft. ")
                if (pull.hasGitHubAutoMerge) append("Auto-merge is on.")
                else if (pull.mergeable == false) append("It has conflicts with the base branch.")
            }
        }
    }.trim()

    fun ago(millis: Long): String {
        val minutes = millis / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes minute${if (minutes == 1L) "" else "s"} ago"
            minutes < 48 * 60 -> (minutes / 60).let { "$it hour${if (it == 1L) "" else "s"} ago" }
            else -> "${minutes / (24 * 60)} days ago"
        }
    }
}
