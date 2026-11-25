package dev.aiauto.core

/**
 * Short replies the app handles itself instead of sending to Claude, so common actions
 * work instantly and don't cost a turn. Anything longer or unrecognized goes to Claude.
 */
enum class VoiceCommand {
    Stop, Status, Merge, FixBuild, Undo, StartOver, Yes, No, Repeat, Quiet;

    companion object {
        private val fillers = setOf("please", "now", "it", "that", "the", "pr", "pull", "request", "ok", "okay", "go", "ahead", "and", "just", "claude", "hey")

        private val phrases = mapOf(
            Stop to setOf("stop", "cancel task", "stop working", "abort"),
            Status to setOf("status", "what's the status", "whats the status", "how's it going", "hows it going", "where are we", "progress"),
            Merge to setOf("merge", "merge this", "ship", "ship this"),
            FixBuild to setOf("fix", "fix build", "fix tests", "fix ci", "fix failure"),
            Undo to setOf("undo", "undo last change", "revert", "revert last change", "take back"),
            StartOver to setOf("start over", "start again", "start from scratch", "begin again"),
            Yes to setOf("yes", "yeah", "yep", "sure", "confirm", "do", "do this"),
            No to setOf("no", "nope", "don't", "dont", "never mind", "nevermind", "not now"),
            Repeat to setOf("repeat", "say again", "what did you say", "come again", "pardon", "sorry what"),
            Quiet to setOf("quiet", "quiet mode", "be quiet", "quieter", "shush", "less talking"),
        )

        /** Returns the command [text] asks for, or null when it should go to Claude. */
        fun parse(text: String): VoiceCommand? {
            val words = text.lowercase().replace(Regex("[^a-z' ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty() || words.size > 6) return null
            val core = words.filterNot { it in fillers }.joinToString(" ")
            val whole = words.joinToString(" ")
            phrases.entries.firstOrNull { (_, set) -> core in set || whole in set }?.let { return it.key }
            // Negation wins over a yes-prefix: "do not merge", "yeah no", "yes, don't" must never confirm.
            if (isNegated(core) || isNegated(whole)) return No
            // "Yes, do it", "sure, merge it": a yes followed by a couple of words still means yes.
            // Bare "do" alone is already Yes above; "do …" with extra words is only Yes when it isn't a negation.
            val first = core.substringBefore(' ')
            val yesWords = phrases.getValue(Yes) - "do"
            return if (core.count { it == ' ' } <= 2 && first in yesWords) Yes else null
        }

        /** True only for a short, unambiguous yes or no, not a longer request that happens to start that way. */
        fun confirmation(text: String): VoiceCommand? {
            val phrase = text.lowercase()
                .replace(Regex("[^a-z' ]"), " ")
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .joinToString(" ")
            return when (phrase) {
                "yes", "yeah", "yep", "sure", "confirm", "do it", "yes start it", "yes go ahead",
                "yes do it", "okay", "ok", "that's right", "thats right" -> Yes
                "no", "nope", "don't", "dont", "never mind", "nevermind", "not now", "cancel" -> No
                else -> null
            }
        }

        private fun isNegated(phrase: String): Boolean {
            val tokens = phrase.split(' ').filter { it.isNotBlank() }
            return tokens.any {
                it == "no" || it == "nope" || it == "not" || it == "never" || it == "nothing" ||
                    it == "don't" || it == "dont"
            }
        }
    }
}
