package dev.aiauto.core

/**
 * What happened while you were out of the car, read aloud when you get back in: tasks that finished, failed or
 * are waiting on you, pull requests that merged, and what Claude has cost today and this month. Short enough to hear while
 * pulling out of a parking space.
 */
object Briefing {
    private const val MAX_NAMED = 2

    /**
     * The briefing for tasks changed after [sinceMillis], or null when there's nothing new worth saying. The month's
     * spend is said alongside today's, with [monthlyLimitUsd] when one is set.
     */
    fun since(
        tasks: List<Task>,
        sinceMillis: Long,
        spentTodayUsd: Double,
        spentThisMonthUsd: Double = 0.0,
        monthlyLimitUsd: Double? = null,
        externalUpdates: List<String> = emptyList(),
    ): String? {
        val changed = tasks.filter { !it.archived && it.updatedMillis > sinceMillis }
        val waiting = tasks.filter { !it.archived && it.status == TaskStatus.NeedsInput }
        val merged = changed.filter { it.prState == PrState.Merged }
        val finished = changed.filter { it.status == TaskStatus.Done && it.prState != PrState.Merged }
        val failed = changed.filter { it.status == TaskStatus.Failed && !it.interrupted }
        val working = tasks.filter { !it.archived && it.isActive }
        if (merged.isEmpty() && finished.isEmpty() && failed.isEmpty() && waiting.isEmpty() && externalUpdates.isEmpty()) return null

        val parts = mutableListOf("Welcome back.")
        if (merged.isNotEmpty()) parts += sentence(merged, "pull request merged", "pull requests merged")
        if (finished.isNotEmpty()) parts += sentence(finished, "task finished", "tasks finished")
        if (failed.isNotEmpty()) parts += sentence(failed, "task failed", "tasks failed")
        if (waiting.isNotEmpty()) parts += sentence(waiting, "task needs your answer", "tasks need your answer")
        if (working.isNotEmpty()) parts += "${count(working.size)} still working."
        externalUpdates.take(MAX_NAMED).forEach { parts += it }
        if (externalUpdates.size > MAX_NAMED) parts += "And ${externalUpdates.size - MAX_NAMED} more repo updates."
        spend(spentTodayUsd, spentThisMonthUsd, monthlyLimitUsd)?.let { parts += it }
        return parts.joinToString(" ")
    }

    private fun spend(todayUsd: Double, monthUsd: Double, monthlyLimitUsd: Double?): String? {
        val today = if (todayUsd > 0) "${SpokenStatus.money(todayUsd)} today" else null
        val month = when {
            monthlyLimitUsd != null -> "${SpokenStatus.money(monthUsd)} of this month's ${SpokenStatus.money(monthlyLimitUsd)} limit"
            // On the first day of a month the two totals match, so saying both would only repeat the number.
            monthUsd > todayUsd -> "${SpokenStatus.money(monthUsd)} this month"
            else -> null
        }
        val said = listOfNotNull(today, month)
        return if (said.isEmpty()) null else "You've spent ${said.joinToString(" and ")}."
    }

    private fun sentence(tasks: List<Task>, one: String, many: String): String {
        val named = tasks.take(MAX_NAMED).joinToString(" and ") { "${it.title} in ${it.repo.name}" }
        val more = tasks.size - MAX_NAMED
        val head = if (tasks.size == 1) "One $one" else "${count(tasks.size)} $many"
        return "$head: $named" + (if (more > 0) ", and $more more." else ".")
    }

    private fun count(n: Int) = when (n) {
        1 -> "One"; 2 -> "Two"; 3 -> "Three"; 4 -> "Four"; 5 -> "Five"
        else -> n.toString()
    }
}
