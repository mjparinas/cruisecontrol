package dev.aiauto.core

import java.util.Locale

/**
 * Keeps Claude within the daily and monthly spending limits and adds each session's cost to the totals.
 * Both limits apply at once: Claude stops when either is reached.
 */
internal class SpendingLimit(
    private val store: TaskStore,
    private val config: EngineConfig,
    private val journal: TaskJournal,
) {
    private class Cap(val name: String, val limitUsd: Double, spentUsd: Double) {
        val leftUsd = limitUsd - spentUsd
        // Within half a cent counts as reached, so rounding never leaves Claude a fraction of a cent to start a turn.
        val reached get() = leftUsd <= 0.005
    }

    // The month comes first so that when it's used up, the message points at it: raising today's limit wouldn't help.
    private fun caps(): List<Cap> = listOfNotNull(
        config.monthlyLimitUsd()?.let { Cap("this month's", it, config.spentThisMonthUsd()) },
        config.dailyLimitUsd()?.let { Cap("today's", it, config.spentTodayUsd()) },
    )

    /** The limit that stops Claude: the first one reached, or else the one with the least left. */
    private fun binding(): Cap? = caps().let { caps -> caps.firstOrNull { it.reached } ?: caps.minByOrNull { it.leftUsd } }

    fun spentTodayUsd(): Double = config.spentTodayUsd()

    /** The sentence said when a task stops at a limit. */
    fun limitMessage(): String {
        val cap = binding()
        val limit = cap?.limitUsd ?: 0.0
        return "Stopped at ${cap?.name ?: "today's"} ${"$"}${"%.2f".format(Locale.US, limit)} spending limit. Raise it in Settings to keep going."
    }

    fun overLimit(): Boolean = caps().any { it.reached }

    /** Throws before Claude is asked to do more work once either limit is reached. */
    fun checkBeforeSending() {
        if (overLimit()) throw IllegalStateException(limitMessage())
    }

    /** Caps the session at what's left of the tighter limit, so Claude stops on its side even if the phone is off. */
    fun capSession(claude: AgentClient, task: Task) {
        val left = caps().minOfOrNull { it.leftUsd } ?: return
        val sessionId = task.sessionId ?: return
        runCatching { claude.setBudget(sessionId, task.costUsd + left.coerceAtLeast(0.0)) }
    }

    /** Records a session's new running total and adds the difference to today's and this month's spend. */
    @Synchronized
    fun recordCost(taskId: String, totalUsd: Double) {
        val task = store.get(taskId) ?: return
        val delta = totalUsd - task.costUsd
        if (delta <= 0.0) return
        journal.update(taskId) { it.copy(costUsd = totalUsd) }
        runCatching { config.addSpend(delta) }
    }

    /** Fetches a session's cost from Claude and records it. Never throws. */
    fun recordSessionCost(claude: AgentClient, taskId: String, sessionId: String) {
        runCatching { claude.costUsd(sessionId) }.getOrNull()?.let { recordCost(taskId, it) }
    }
}
