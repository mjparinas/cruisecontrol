package dev.aiauto.core

/**
 * Turns a request said to Google Assistant, like "add dark mode to aiauto", into a task: which saved repo it's for,
 * what Claude is asked to do and what the app says back. The repo can be named at the start ("in aiauto, add a
 * toggle") or the end ("add a toggle to aiauto"); otherwise the last-used repo is picked.
 */
object VoiceShortcut {
    sealed interface Result {
        /** What to say back, as a plain sentence. */
        val spoken: String
    }

    /** Start [request] on [repo]. */
    data class Start(val repo: RepoRef, val request: String, override val spoken: String) : Result

    /** Nothing can start; [spoken] says why and what to do. */
    data class Problem(override val spoken: String) : Result

    private val prepositions = setOf("in", "on", "to", "for")
    private val repoWords = setOf("repo", "repository", "project")
    private const val MAX_REPO_WORDS = 6

    fun resolve(
        spoken: String,
        repos: List<RepoRef>,
        lastUsed: RepoRef?,
        templates: List<TaskTemplate>,
        configured: Boolean,
    ): Result {
        if (!configured) return Problem("Add your Claude key and GitHub token in Cruise Control first.")
        if (repos.isEmpty()) return Problem("Add a repo in Cruise Control first.")
        val said = spoken.trim().removePrefix("to ").trim()
        if (said.isEmpty()) return Problem("I didn't catch what you want Claude to do. Try again, like add dark mode to ${repos.first().name}.")

        val (named, rest) = splitRepo(said, repos) ?: (null to said)
        val request = rest.trim().trimEnd('.', '!', '?', ',').trim()
        if (request.isEmpty()) return Problem("I didn't catch what you want Claude to do" + named?.let { " in ${it.name}" }.orEmpty() + ".")
        val repo = named
            ?: lastUsed?.takeIf { it in repos }
            ?: repos.singleOrNull()
            ?: return Problem("Which repo is that for? Say it at the end, like add dark mode to ${repos.first().name}.")

        val template = templates.firstOrNull { norm(it.name) == norm(request) }
        return if (template != null) {
            Start(repo, template.prompt, "Starting ${template.name} on ${repo.name}.")
        } else {
            Start(repo, request, "Starting on ${repo.name}: $request.")
        }
    }

    /** The saved repo named at the start or end of [said], and the request without it. Null when none is named. */
    private fun splitRepo(said: String, repos: List<RepoRef>): Pair<RepoRef, String>? {
        val words = said.split(Regex("\\s+"))
        fun match(part: List<String>): RepoRef? {
            val name = part.dropWhile { it.equals("the", ignoreCase = true) }
                .let { if (it.size > 1 && norm(it.last()) in repoWords) it.dropLast(1) else it }
                .joinToString(" ")
            return RepoRef.matchSpoken(name.trimEnd('.', '!', '?', ','), repos).singleOrNull()
        }
        // "add dark mode to aiauto": the last few words after a preposition.
        for (size in 1..MAX_REPO_WORDS) {
            val at = words.size - size - 1
            if (at < 1) break
            if (norm(words[at]) !in prepositions) continue
            match(words.subList(at + 1, words.size))?.let { return it to words.subList(0, at).joinToString(" ") }
        }
        // "in aiauto, add dark mode" or "in aiauto add dark mode": a preposition first, then the repo.
        if (words.size > 1 && norm(words[0]) in prepositions) {
            for (size in MAX_REPO_WORDS downTo 1) {
                if (size + 1 > words.size) continue
                match(words.subList(1, size + 1))?.let { return it to words.subList(size + 1, words.size).joinToString(" ") }
            }
        }
        return null
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
