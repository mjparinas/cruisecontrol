package dev.aiauto.core

/**
 * The search box at the top of Settings. A setting shows when every word typed appears in its section, its title or a
 * few extra words people might search for, like "colour" for the accent or "money" for the daily limit.
 *
 * [CATALOGUE] lists each section's rows by their on-screen title, so keep it in step with the Settings screen.
 * Rows it doesn't list, like each saved repo, show whenever their section does.
 */
class SettingsSearch(query: String) {
    private val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }

    /** True while something is typed. */
    val active: Boolean get() = words.isNotEmpty()

    fun showsSection(section: String): Boolean =
        !active || matches(section) || CATALOGUE[section].orEmpty().any { matches("$section ${it.title} ${it.keywords}") }

    fun showsRow(section: String?, title: String): Boolean {
        if (!active || section == null || matches(section)) return true
        val setting = CATALOGUE[section]?.firstOrNull { it.title == title } ?: return showsSection(section)
        return matches("$section ${setting.title} ${setting.keywords}")
    }

    /** True when nothing on the page matches, so the screen can say so. */
    val nothingFound: Boolean get() = active && CATALOGUE.keys.none { showsSection(it) }

    private fun matches(text: String): Boolean {
        val haystack = text.lowercase()
        return words.all { it in haystack }
    }

    data class Setting(val title: String, val keywords: String = "")

    companion object {
        val CATALOGUE: Map<String, List<Setting>> = linkedMapOf(
            "Accounts" to listOf(
                Setting("Claude API key", "anthropic key secret password login"),
                Setting("GitHub token", "key secret password login"),
                Setting("OpenAI API key", "chatgpt gpt secret password car chat"),
                Setting("Gemini API key", "google secret password car chat"),
            ),
            // Each repo's row opens its pull request switches and repo rules, so searches for those find the repos.
            "Repositories" to listOf(
                Setting(
                    "Add repositories",
                    "repo repos project github pull requests pr merge auto-merge conflicts failing checks tests ci release rules",
                ),
            ),
            "In the car" to listOf(
                Setting("Read updates aloud", "speak voice android auto talk sound"),
                Setting("Quiet mode", "silent less fewer updates"),
                Setting("Speaking speed", "voice rate fast slow speech"),
                Setting("Voice", "speech speak"),
                Setting("Listen when opened", "microphone mic hands-free start"),
                Setting("Keep listening until Stop", "microphone mic dictation"),
                Setting("Briefing when you get in", "summary welcome back"),
                Setting("Remember past drives", "memory history chat"),
                Setting("Forget past drives", "memory history clear delete chat"),
            ),
            "Claude" to listOf(
                Setting("Model for tasks", "opus sonnet haiku ai"),
                Setting("Effort for tasks", "thinking ai"),
                Setting("Model for the car chat", "opus sonnet haiku ai openai gpt gemini"),
                Setting("Effort for the car chat", "thinking ai"),
                Setting("Allow web search", "internet browse"),
                Setting("Standing rules", "instructions"),
            ),
            "Appearance" to listOf(
                Setting("Theme", "dark light mode night"),
                Setting("Accent color", "colour palette"),
            ),
            "Spending" to listOf(
                Setting("Daily limit", "money cost budget dollars price"),
                Setting("Monthly limit", "money cost budget dollars price"),
            ),
            "Sync" to listOf(
                Setting("Sync across devices", "gist tablet backup cloud"),
                Setting("Sync now", "gist cloud"),
            ),
            "About" to listOf(
                Setting("Cruise Control", "version"),
                Setting("Check for updates", "update upgrade release new version"),
            ),
        )
    }
}
