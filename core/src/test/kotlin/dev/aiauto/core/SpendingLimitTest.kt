package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The daily and monthly spending limits: what counts toward the totals, when Claude is stopped, and the budget it gets. */
class SpendingLimitTest {
    private val repo = RepoRef("me", "app")

    private class MapStore : TaskStore {
        val map = LinkedHashMap<String, Task>()
        override fun get(id: String) = map[id]
        override fun all() = map.values.toList()
        override fun put(task: Task) { map[task.id] = task }
        override fun remove(id: String) { map.remove(id) }
    }

    private class Config : EngineConfig {
        var limit: Double? = null
        var spent = 0.0
        var monthLimit: Double? = null
        var monthSpent = 0.0
        var spendFails = false
        override fun anthropicKey() = "key"
        override fun githubToken() = "token"
        override fun agentSetup() = AgentSetup("agent", 1, "env", AgentProfile())
        override fun saveAgentSetup(setup: AgentSetup) {}
        override fun dailyLimitUsd() = limit
        override fun spentTodayUsd() = spent
        override fun monthlyLimitUsd() = monthLimit
        override fun spentThisMonthUsd() = monthSpent
        override fun addSpend(usd: Double) {
            if (spendFails) error("disk full")
            spent += usd
            monthSpent += usd
        }
    }

    private class FakeClaude : AgentClient {
        val budgets = mutableListOf<Pair<String, Double>>()
        var budgetFails = false
        var cost: () -> Double? = { null }

        override fun createSetup(profile: AgentProfile, existing: AgentSetup?) = AgentSetup("agent", 1, "env", profile)
        override fun createSession(setup: AgentSetup, task: Task, githubToken: String) = "session"
        override fun sendPrompt(sessionId: String, prompt: String) {}
        override fun follow(
            sessionId: String,
            seenEventIds: MutableSet<String>,
            onConnected: () -> Unit,
            shouldStop: () -> Boolean,
            canReview: () -> Boolean,
            onUpdate: (AgentUpdate) -> Unit,
        ) = true
        override fun setBudget(sessionId: String, maxUsd: Double) {
            if (budgetFails) error("network down")
            budgets += sessionId to maxUsd
        }
        override fun costUsd(sessionId: String) = cost()
        override fun close() {}
    }

    private val store = MapStore()
    private val config = Config()
    private val claude = FakeClaude()
    private val journal = TaskJournal(store, clock = { 0L }, onMessage = { _, _ -> }, onProgress = { _, _ -> }, quiet = { false })
    private val spending = SpendingLimit(store, config, journal)

    private fun task(id: String = "t", sessionId: String? = "s", costUsd: Double = 0.0) =
        Task(id, repo, "x", "b", sessionId = sessionId, costUsd = costUsd, createdMillis = 0, updatedMillis = 0).also(store::put)

    @Test fun addsOnlyTheIncreaseToTodaysTotal() {
        task()
        spending.recordCost("t", 0.40)
        spending.recordCost("t", 1.25)
        assertEquals(1.25, store.get("t")!!.costUsd, 1e-9)
        assertEquals(1.25, config.spent, 1e-9)
        assertEquals(1.25, config.monthSpent, 1e-9)
    }

    @Test fun neverCountsTheSameCostTwice() {
        task()
        spending.recordCost("t", 1.25)
        // A reconnect replays the last usage event, and the final fetch after a turn usually reports the same total.
        spending.recordCost("t", 1.25)
        spending.recordCost("t", 1.00)
        assertEquals(1.25, store.get("t")!!.costUsd, 1e-9)
        assertEquals(1.25, config.spent, 1e-9)
    }

    @Test fun eachTaskAddsItsOwnCost() {
        task("a")
        task("b", sessionId = "s2")
        spending.recordCost("a", 2.0)
        spending.recordCost("b", 0.5)
        spending.recordCost("a", 2.5)
        assertEquals(3.0, config.spent, 1e-9)
        assertEquals(3.0, spending.spentTodayUsd(), 1e-9)
    }

    @Test fun ignoresCostsForATaskThatIsGone() {
        spending.recordCost("cleared", 3.0)
        assertEquals(0.0, config.spent)
        assertTrue(store.map.isEmpty())
    }

    @Test fun aFailingSpendStoreStillKeepsTheTasksCost() {
        task()
        config.spendFails = true
        spending.recordCost("t", 0.75)
        assertEquals(0.75, store.get("t")!!.costUsd, 1e-9)
    }

    @Test fun noLimitMeansNeverOver() {
        config.spent = 1_000.0
        assertFalse(spending.overLimit())
        spending.checkBeforeSending()
    }

    @Test fun overOnceTodaysSpendReachesTheLimitToTheCent() {
        config.limit = 5.0
        config.spent = 4.99
        assertFalse(spending.overLimit())
        // Within half a cent counts as reached, so rounding never leaves Claude a fraction of a cent to start a turn.
        config.spent = 4.996
        assertTrue(spending.overLimit())
        config.spent = 5.0
        assertTrue(spending.overLimit())
        config.spent = 6.0
        assertTrue(spending.overLimit())
    }

    @Test fun refusesToSendOnceTheLimitIsReached() {
        config.limit = 7.5
        config.spent = 2.0
        spending.checkBeforeSending()
        config.spent = 7.5
        val e = assertFailsWith<IllegalStateException> { spending.checkBeforeSending() }
        assertEquals("Stopped at today's \$7.50 spending limit. Raise it in Settings to keep going.", e.message)
    }

    @Test fun overOnceThisMonthsSpendReachesTheMonthlyLimit() {
        config.monthLimit = 100.0
        config.monthSpent = 99.99
        assertFalse(spending.overLimit())
        config.monthSpent = 99.996
        assertTrue(spending.overLimit())
        config.monthSpent = 120.0
        assertTrue(spending.overLimit())
    }

    @Test fun respectsBothLimitsAtOnce() {
        config.limit = 20.0
        config.monthLimit = 100.0
        config.spent = 5.0
        config.monthSpent = 50.0
        assertFalse(spending.overLimit())
        // A quiet day still stops once the month is used up.
        config.monthSpent = 100.0
        assertTrue(spending.overLimit())
        // And a month with room left still stops at the day's limit.
        config.monthSpent = 60.0
        config.spent = 20.0
        assertTrue(spending.overLimit())
    }

    @Test fun refusesToSendOnceTheMonthlyLimitIsReached() {
        config.monthLimit = 150.0
        config.monthSpent = 150.0
        val e = assertFailsWith<IllegalStateException> { spending.checkBeforeSending() }
        assertEquals("Stopped at this month's \$150.00 spending limit. Raise it in Settings to keep going.", e.message)
    }

    @Test fun namesTheMonthWhenBothLimitsAreReached() {
        // Raising today's limit wouldn't help, so the driver hears about the month.
        config.limit = 10.0
        config.spent = 12.0
        config.monthLimit = 100.0
        config.monthSpent = 100.0
        assertTrue(spending.limitMessage().startsWith("Stopped at this month's \$100.00 spending limit"))
    }

    @Test fun namesTheTighterLimitWhenClaudeStopsOnItsSide() {
        config.limit = 20.0
        config.spent = 5.0
        config.monthLimit = 100.0
        config.monthSpent = 98.0
        assertTrue(spending.limitMessage().startsWith("Stopped at this month's"))
        config.monthSpent = 50.0
        assertTrue(spending.limitMessage().startsWith("Stopped at today's \$20.00"))
    }

    @Test fun capsTheSessionAtWhatIsLeftOfTheTighterLimit() {
        config.limit = 10.0
        config.spent = 4.0
        config.monthLimit = 100.0
        config.monthSpent = 98.0
        spending.capSession(claude, task(costUsd = 1.5))
        config.monthSpent = 50.0
        spending.capSession(claude, task(costUsd = 1.5))
        assertEquals(listOf("s" to 3.5, "s" to 7.5), claude.budgets)
    }

    @Test fun aMonthlyLimitAloneCapsTheSession() {
        config.monthLimit = 30.0
        config.monthSpent = 25.0
        spending.capSession(claude, task(costUsd = 1.0))
        assertEquals(listOf("s" to 6.0), claude.budgets)
    }

    @Test fun capsTheSessionAtItsCostSoFarPlusWhatIsLeftToday() {
        config.limit = 10.0
        config.spent = 4.0
        spending.capSession(claude, task(costUsd = 1.5))
        assertEquals(listOf("s" to 7.5), claude.budgets)
    }

    @Test fun overspentDayCapsTheSessionAtWhatItAlreadyCost() {
        config.limit = 10.0
        config.spent = 12.0
        spending.capSession(claude, task(costUsd = 3.0))
        assertEquals(listOf("s" to 3.0), claude.budgets)
    }

    @Test fun leavesTheSessionUncappedWithoutALimitOrASession() {
        config.spent = 4.0
        spending.capSession(claude, task())
        config.limit = 10.0
        spending.capSession(claude, task(sessionId = null))
        assertTrue(claude.budgets.isEmpty())
    }

    @Test fun aFailedCapDoesNotStopTheTask() {
        config.limit = 10.0
        claude.budgetFails = true
        spending.capSession(claude, task())
        assertTrue(claude.budgets.isEmpty())
    }

    @Test fun recordsTheSessionCostClaudeReports() {
        task()
        claude.cost = { 0.9 }
        spending.recordSessionCost(claude, "t", "s")
        assertEquals(0.9, store.get("t")!!.costUsd, 1e-9)
        assertEquals(0.9, config.spent, 1e-9)
    }

    @Test fun aMissingOrFailedSessionCostChangesNothing() {
        task(costUsd = 0.5)
        claude.cost = { null }
        spending.recordSessionCost(claude, "t", "s")
        claude.cost = { throw IllegalStateException("timeout") }
        spending.recordSessionCost(claude, "t", "s")
        assertEquals(0.5, store.get("t")!!.costUsd, 1e-9)
        assertEquals(0.0, config.spent)
    }
}
