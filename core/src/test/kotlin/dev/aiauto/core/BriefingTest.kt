package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BriefingTest {
    private val repo = RepoRef("me", "app")
    private fun task(id: String, status: TaskStatus, updated: Long, pr: PrState? = null, title: String = "task $id") =
        Task(id, repo, title, "b-$id", status = status, createdMillis = 0, updatedMillis = updated, prState = pr, prNumber = pr?.let { 1 })

    @Test fun nothingNewMeansNoBriefing() {
        assertNull(Briefing.since(listOf(task("a", TaskStatus.Done, 50)), sinceMillis = 100, spentTodayUsd = 3.0))
    }

    @Test fun summarizesWhatChanged() {
        val text = Briefing.since(
            listOf(
                task("a", TaskStatus.Done, 200, PrState.Merged, "Fix login"),
                task("b", TaskStatus.Done, 200, title = "Add tests"),
                task("c", TaskStatus.NeedsInput, 10, title = "Rename screen"),
                task("d", TaskStatus.Working, 200),
            ),
            sinceMillis = 100,
            spentTodayUsd = 1.5,
        )!!
        assertEquals(
            "Welcome back. One pull request merged: Fix login in app. One task finished: Add tests in app. " +
                "One task needs your answer: Rename screen in app. One still working. You've spent $1.50 today.",
            text,
        )
    }

    @Test fun saysThisMonthsSpend() {
        val tasks = listOf(task("a", TaskStatus.Done, 200, title = "Add tests"))
        fun spend(today: Double, month: Double, limit: Double?) =
            Briefing.since(tasks, 100, today, month, limit)!!.substringAfter("in app. ")
        assertEquals("You've spent $1.50 today and $12.00 this month.", spend(1.5, 12.0, null))
        assertEquals("You've spent $1.50 today and $12.00 of this month's $50.00 limit.", spend(1.5, 12.0, 50.0))
        assertEquals("You've spent $12.00 this month.", spend(0.0, 12.0, null))
        assertEquals("You've spent $1.50 today.", spend(1.5, 1.5, null))
        assertEquals("You've spent $0 of this month's $50.00 limit.", spend(0.0, 0.0, 50.0))
        assertTrue("spent" !in Briefing.since(tasks, 100, 0.0, 0.0, null)!!)
    }

    @Test fun namesAtMostTwo() {
        val text = Briefing.since((1..4).map { task("$it", TaskStatus.Failed, 200) }, 100, 0.0)!!
        assertTrue("Four tasks failed: task 1 in app and task 2 in app, and 2 more." in text, text)
    }

    @Test fun memoryKeepsOnlyEarlierExchanges() {
        val now = 10 * 60 * 60_000L
        val exchanges = listOf(ChatExchange("hi", "hello", now - 3 * 60 * 60_000L), ChatExchange("now", "yes", now - 1000))
        val text = ChatMemory.describe(exchanges, beforeMillis = now - 60_000, now = now)!!
        assertEquals("[3 h ago] Driver: hi\nYou: hello", text)
        assertNull(ChatMemory.describe(exchanges.drop(1), beforeMillis = now - 60_000, now = now))
    }
}
