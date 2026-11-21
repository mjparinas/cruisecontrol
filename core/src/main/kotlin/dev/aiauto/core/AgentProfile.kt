package dev.aiauto.core

import kotlinx.serialization.Serializable

/**
 * The Claude models the app lets you pick, with their list prices in US dollars per million tokens. [cacheReadPerMTok]
 * is the price of a prompt cache hit, which Opus 5.5 and Sonnet 5.5 charge at 0.05 times input instead of 0.1.
 */
enum class ModelChoice(
    val id: String,
    val label: String,
    val blurb: String,
    val inputPerMTok: Double,
    val outputPerMTok: Double,
    val cacheReadPerMTok: Double,
) {
    Opus("claude-opus-5-5", "Opus 5.5", "Most capable", 4.0, 20.0, 0.20),
    Sonnet("claude-sonnet-5-5", "Sonnet 5.5", "Faster, about half the price", 2.0, 10.0, 0.10),
    Haiku("claude-haiku-5-5", "Haiku 5.5", "Fastest and cheapest, for small jobs", 0.10, 0.50, 0.01),
    ;

    fun costUsd(inputTokens: Long, outputTokens: Long, cacheWriteTokens: Long = 0, cacheReadTokens: Long = 0): Double =
        promptCostUsd(inputTokens, outputTokens, cacheWriteTokens, cacheReadTokens, inputPerMTok, outputPerMTok, cacheReadPerMTok)

    companion object {
        fun of(id: String?): ModelChoice = entries.firstOrNull { it.id == id || it.name == id } ?: Opus
    }
}

/** Prompt cache writes with the default 5-minute lifetime cost 1.25 times input; reads cost [cacheReadPerMTok]. */
internal fun promptCostUsd(
    inputTokens: Long,
    outputTokens: Long,
    cacheWriteTokens: Long,
    cacheReadTokens: Long,
    inputPerMTok: Double,
    outputPerMTok: Double,
    cacheReadPerMTok: Double,
): Double = (
    inputTokens * inputPerMTok + cacheWriteTokens * inputPerMTok * 1.25 +
        cacheReadTokens * cacheReadPerMTok + outputTokens * outputPerMTok
    ) / 1_000_000.0

/** How hard Claude thinks before it answers. Higher is slower and costs more, and is better on hard problems. */
enum class EffortChoice(val label: String, val blurb: String) {
    Low("Low", "Quickest and cheapest"),
    Medium("Medium", "Balanced"),
    High("High", "More thorough"),
    XHigh("Extra high", "Best for tricky coding work"),
    Max("Max", "Slowest and most expensive"),
    ;

    companion object {
        fun of(name: String?, default: EffortChoice): EffortChoice = entries.firstOrNull { it.name == name } ?: default
    }
}

/** Common standing rules you can tick in Settings. Each one adds a line to what Claude follows on every task. */
enum class StandingRule(val label: String, val instruction: String) {
    AddTests("Always add or update tests", "Add or update tests for every change you make, and run them."),
    SmallChanges("Keep changes small", "Keep each change small and focused; leave unrelated cleanups for a separate request."),
    MatchStyle("Follow the existing code style", "Match the existing code style, naming and structure of the repository."),
    NoNewDependencies("No new dependencies", "Do not add new libraries or dependencies unless the request asks for one."),
    UpdateDocs("Update the README and docs", "Update the README and any other docs that the change affects."),
    LeaveCi("Don't touch CI files", "Do not change CI or build pipeline files, such as anything under .github/workflows."),
    Changelog("Add a changelog entry", "Add a short entry to the changelog when the repository has one."),
    ExplainWhy("Explain why in commits", "Write commit messages that say why the change was made, not only what changed."),
    ;

    companion object {
        fun of(names: Collection<String>): List<StandingRule> = entries.filter { it.name in names }
    }
}

/**
 * Everything about the coding agent that you can change in Settings. When it changes, the app makes a new
 * agent with these settings for the next task; tasks already running keep the agent they started with.
 */
@Serializable
data class AgentProfile(
    val model: String = ModelChoice.Opus.id,
    val effort: String = EffortChoice.High.name,
    val webSearch: Boolean = false,
    val rules: List<String> = emptyList(),
    val instructions: String = "",
) {
    val modelChoice: ModelChoice get() = ModelChoice.of(model)
    val effortChoice: EffortChoice get() = EffortChoice.of(effort, EffortChoice.High)

    /** The agent's system prompt: the app's own rules, then yours. */
    fun systemPrompt(base: String): String {
        val lines = StandingRule.of(rules).map { it.instruction } +
            instructions.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return base
        return base + "\n\nStanding rules from the user, to follow on every task unless the request says otherwise:\n" +
            lines.joinToString("\n") { "- $it" }
    }
}

/** The model and effort for the spoken chat in the car. Low effort by default, so answers come back quickly. */
data class ChatProfile(
    val model: ChatModel = ChatModel.Opus,
    val effort: EffortChoice = EffortChoice.Low,
)
