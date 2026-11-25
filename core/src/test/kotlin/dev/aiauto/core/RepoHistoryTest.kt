package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The full conversation history per task and per repo, as the car shows it and reads it aloud. */
class RepoHistoryTest {
    private val app = RepoRef("me", "app")
    private val other = RepoRef("me", "other")

    private fun task(id: String, repo: RepoRef, created: Long, vararg lines: Pair<Speaker, String>, archived: Boolean = false) = Task(
        id = id,
        repo = repo,
        title = "Task $id",
        branch = "claude/$id",
        messages = lines.mapIndexed { i, (speaker, text) -> TaskMessage("m-$id-$i", speaker, text, created + i) },
        createdMillis = created,
        updatedMillis = created,
        archived = archived,
    )

    @Test
    fun repoHistoryHoldsEveryTaskInTheRepoNewestFirst() {
        val older = task("a", app, 100, Speaker.You to "Fix the build")
        val newer = task("b", app, 200, Speaker.You to "Add dark mode")
        val elsewhere = task("c", other, 150, Speaker.You to "Something else")

        assertEquals(listOf("b", "a"), RepoHistory.tasks(listOf(older, elsewhere, newer), app).map { it.id })
    }

    @Test
    fun archivedAndClearedTasksRemainInCarRepoHistory() {
        val archived = task("a", app, 100, Speaker.You to "Fix the build", archived = true)
        val active = task("b", app, 200, Speaker.You to "Add dark mode")
        val cleared = task("c", app, 300, Speaker.You to "Fix login").copy(cleared = true, clearedAtMillis = 400)

        assertEquals(listOf("c", "b", "a"), RepoHistory.tasks(listOf(archived, active, cleared), app).map { it.id })
        assertEquals(
            listOf("1 task in app, newest first.", "Task a. Archived. No reply from Claude yet."),
            RepoHistory.spokenRepo(listOf(archived), app),
        )
        assertEquals(
            listOf("1 task in app, newest first.", "Task c. Cleared. No reply from Claude yet."),
            RepoHistory.spokenRepo(listOf(cleared), app),
        )
    }

    @Test
    fun clearedHistoryKeepsTheMostRecentlyClearedHundredTasks() {
        val tasks = (1..(RepoHistory.CLEARED_TASKS_KEEP + 5)).map { id ->
            task("$id", app, id.toLong()).copy(cleared = true, clearedAtMillis = id.toLong())
        }
        val kept = RepoHistory.keepCleared(tasks)
        assertEquals(RepoHistory.CLEARED_TASKS_KEEP, kept.size)
        assertEquals("6", kept.first().id)
        assertEquals("105", kept.last().id)
    }

    @Test
    fun repoMatchesIgnoringCase() {
        val t = task("a", RepoRef("Me", "App"), 100)
        assertEquals(listOf("a"), RepoHistory.tasks(listOf(t), app).map { it.id })
    }

    @Test
    fun messagesAreNewestFirstSoTheCarShowsTheLatest() {
        val t = task("a", app, 100, Speaker.You to "Fix it", Speaker.Claude to "On it.", Speaker.Claude to "Done.")
        assertEquals(listOf("Done.", "On it.", "Fix it"), RepoHistory.messages(t).map { it.text })
    }

    @Test
    fun sessionHistoryFillsOlderGapsWithoutReplacingLocalStatus() {
        val local = listOf(
            TaskMessage("local-1", Speaker.You, "Follow-up", 30),
            TaskMessage("local-2", Speaker.Status, "Pull request opened", 40),
        )
        val session = listOf(
            TaskMessage("remote-1", Speaker.You, "Original request", 10),
            TaskMessage("remote-2", Speaker.Claude, "Earlier answer", 20),
            TaskMessage("remote-3", Speaker.You, "Follow-up", 30),
            TaskMessage("remote-4", Speaker.Claude, "Latest answer", 50),
        )

        assertEquals(
            listOf("Original request", "Earlier answer", "Follow-up", "Pull request opened"),
            RepoHistory.mergeSessionMessages(local, session).map { it.text },
        )
    }

    @Test
    fun shortHistoryFitsOnOnePage() {
        val page = RepoHistory.page((1..6).toList(), rows = 6)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), page.items)
        assertEquals(0, page.newer)
        assertEquals(0, page.older)
    }

    @Test
    fun longHistoryKeepsARowForOlderMessages() {
        val page = RepoHistory.page((1..20).toList(), rows = 6)
        assertEquals(listOf(1, 2, 3, 4, 5), page.items)
        assertEquals(15, page.older)
    }

    @Test
    fun laterPagesKeepRowsForBothDirections() {
        val middle = RepoHistory.page((1..20).toList(), rows = 6, skip = 5)
        assertEquals(listOf(6, 7, 8, 9), middle.items)
        assertEquals(5, middle.newer)
        assertEquals(11, middle.older)

        val last = RepoHistory.page((1..20).toList(), rows = 6, skip = 17)
        assertEquals(listOf(18, 19, 20), last.items)
        assertEquals(0, last.older)
    }

    @Test
    fun skippingPastTheEndShowsTheOldestItem() {
        val page = RepoHistory.page(listOf(1, 2, 3), rows = 6, skip = 9)
        assertEquals(listOf(3), page.items)
    }

    @Test
    fun spokenTaskReadsEveryMessageOldestFirstIncludingClaudes() {
        val t = task("a", app, 100, Speaker.You to "Fix the build", Speaker.Claude to "Looking now.", Speaker.Status to "Pull request 3 is open", Speaker.Claude to "Fixed it.")

        assertEquals(
            listOf("Task a. 4 messages.", "You said: Fix the build", "Claude said: Looking now.", "Pull request 3 is open", "Claude said: Fixed it."),
            RepoHistory.spokenTask(t),
        )
    }

    @Test
    fun spokenRepoGivesEachTaskAndClaudesLatestWordNewestFirst() {
        val first = task("a", app, 100, Speaker.You to "Fix it", Speaker.Claude to "Fixed the build.")
        val second = task("b", app, 200, Speaker.You to "Add dark mode")

        assertEquals(
            listOf("2 tasks in app, newest first.", "Task b. No reply from Claude yet.", "Task a. Claude said: Fixed the build."),
            RepoHistory.spokenRepo(listOf(first, second), app),
        )
        assertEquals(listOf("There are no tasks in other on this phone."), RepoHistory.spokenRepo(listOf(first), other))
    }

    @Test
    fun longMessagesAreSplitForTheSpeechEngine() {
        val text = (1..400).joinToString(" ") { "Sentence number $it." }
        val parts = RepoHistory.chunks(text)

        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= RepoHistory.SPOKEN_CHARS })
        assertTrue(parts.all { it.endsWith(".") })
        assertEquals(text, parts.joinToString(" "))
    }

    @Test
    fun aWordLongerThanTheLimitIsStillSplit() {
        val parts = RepoHistory.chunks("x".repeat(25), max = 10)
        assertEquals(listOf(10, 10, 5), parts.map { it.length })
    }
}
