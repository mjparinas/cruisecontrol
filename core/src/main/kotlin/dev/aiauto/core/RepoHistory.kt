package dev.aiauto.core

/**
 * The conversation history kept on this phone, for the car's History screens: every message of a task, and every
 * task in a repo. Archived tasks stay hidden from the car, cleared tasks are gone for good, and progress notes replace
 * each other, so only the latest one stays. While driving the car shows one short page and the rest is read aloud.
 */
object RepoHistory {
    /** The most rows a car list shows, the same as the other car lists. */
    const val CAR_ROWS = 6

    /** Text-to-speech engines refuse long input (about 4,000 characters), so read-aloud lines stay under this. */
    const val SPOKEN_CHARS = 3_000

    /** One page of a newest-first list: what to show, and how many items are newer or older than it. */
    data class Page<T>(val items: List<T>, val newer: Int, val older: Int)

    /** Every task in [repo] that isn't archived, matched on its full name ignoring case, newest first. */
    fun tasks(all: List<Task>, repo: RepoRef): List<Task> =
        all.filter { !it.archived && it.repo.fullName.equals(repo.fullName, ignoreCase = true) }.sortedByDescending { it.createdMillis }

    /** A task's messages, newest first, so the car's first page shows the latest. */
    fun messages(task: Task): List<TaskMessage> = task.messages.asReversed()

    /**
     * The page of [newestFirst] that starts after skipping [skip] items. When there's more on either side, a row is
     * kept for each direction, so the page and its "newer" and "older" rows fit in [rows].
     */
    fun <T> page(newestFirst: List<T>, rows: Int, skip: Int = 0): Page<T> {
        val start = skip.coerceIn(0, maxOf(0, newestFirst.size - 1))
        val newerRow = if (start > 0) 1 else 0
        val room = (rows - newerRow).coerceAtLeast(1)
        val fitsAll = newestFirst.size - start <= room
        val items = newestFirst.drop(start).take(if (fitsAll) room else (room - 1).coerceAtLeast(1))
        return Page(items, newer = start, older = newestFirst.size - start - items.size)
    }

    /** Who said a line, as the car shows it. */
    fun who(message: TaskMessage): String = when (message.speaker) {
        Speaker.You -> "You"
        Speaker.Claude -> "Claude"
        Speaker.Status -> "Status"
    }

    /** A task's whole conversation to read aloud, oldest first, each line short enough for the speech engine. */
    fun spokenTask(task: Task): List<String> {
        val intro = "${task.title}. " + count(task.messages.size, "message") + "."
        return listOf(intro) + task.messages.flatMap { m ->
            val lead = when (m.speaker) {
                Speaker.You -> "You said: "
                Speaker.Claude -> "Claude said: "
                Speaker.Status -> ""
            }
            chunks(lead + m.text)
        }
    }

    /** A repo's tasks to read aloud, newest first: each task's request and Claude's latest message. */
    fun spokenRepo(all: List<Task>, repo: RepoRef): List<String> {
        val tasks = tasks(all, repo)
        if (tasks.isEmpty()) return listOf("There are no tasks in ${repo.name} on this phone.")
        val intro = count(tasks.size, "task") + " in ${repo.name}, newest first."
        return listOf(intro) + tasks.flatMap { task ->
            val last = task.messages.lastOrNull { it.speaker == Speaker.Claude }
            chunks("${task.title}." + (last?.let { " Claude said: ${it.text}" } ?: " No reply from Claude yet."))
        }
    }

    private fun count(n: Int, noun: String) = "$n $noun" + if (n == 1) "" else "s"

    /** Splits [text] into pieces of at most [max] characters, at sentence ends or spaces when it can. */
    internal fun chunks(text: String, max: Int = SPOKEN_CHARS): List<String> {
        val out = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > max) {
            val window = rest.substring(0, max)
            val sentence = Regex("""[.!?]\s""").findAll(window).map { it.range.first + 1 }.lastOrNull()
            val cut = sentence ?: window.lastIndexOf(' ').takeIf { it > 0 } ?: max
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
