package dev.aiauto.core

/** Where tasks live. The Android app backs this with a file; tests use a map. */
interface TaskStore {
    fun get(id: String): Task?
    fun all(): List<Task>
    fun put(task: Task)
    fun remove(id: String)
}

/** Credentials and one-time setup the engine needs. Returning null means "not configured yet". */
interface EngineConfig {
    fun anthropicKey(): String?
    fun githubToken(): String?
    fun agentSetup(): AgentSetup?
    fun saveAgentSetup(setup: AgentSetup)

    /** This device's id when tasks sync between devices, or null when they don't. */
    fun deviceId(): String? = null

    /** The model, effort, web search and standing rules new tasks should use. */
    fun agentProfile(): AgentProfile = AgentProfile()

    /** Quiet mode: only announce each turn's result, failures and pull requests, not every step. */
    fun quietMode(): Boolean = false

    /** The most Claude may spend per day across all tasks, in US dollars. Null means no limit. */
    fun dailyLimitUsd(): Double? = null
    /** What has been spent today so far. */
    fun spentTodayUsd(): Double = 0.0
    /** The most Claude may spend per calendar month across all tasks, in US dollars. Null means no limit. */
    fun monthlyLimitUsd(): Double? = null
    /** What has been spent this month so far: every day's spend added up. */
    fun spentThisMonthUsd(): Double = 0.0
    /** Adds a cost to both today's and this month's totals. */
    fun addSpend(usd: Double) {}

    /** Merge a task's pull request once its checks pass. */
    fun autoMerge(): Boolean = false

    /** Ask Claude to fix failing checks on its pull request without waiting for the driver. */
    fun autoFixChecks(): Boolean = false

    /** Ask Claude to resolve merge conflicts on its pull request without waiting for the driver. */
    fun autoFixConflicts(): Boolean = false

    /** When auto-release was switched on, or null when it's off. Only pull requests merged after this get a release. */
    fun autoReleaseSince(): Long? = null
}
