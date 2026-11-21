package dev.aiauto.core

/**
 * Short replies the app handles itself instead of sending to Claude, so common actions
 * work instantly and don't cost a turn. Anything longer or unrecognized goes to Claude.
 */
enum class VoiceCommand {
    Stop, Status, Merge, FixBuild, Yes, No;

    companion object {
        private val fillers = setOf("please", "now", "it", "that", "the", "pr", "pull", "request", "ok", "okay", "go", "ahead", "and", "just", "claude", "hey")

        private val phrases = mapOf(
            Stop to setOf("stop", "cancel task", "stop working", "abort"),
            Status to setOf("status", "what's the status", "whats the status", "how's it going", "hows it going", "where are we", "progress"),
            Merge to setOf("merge", "merge this", "ship", "ship this"),
            FixBuild to setOf("fix", "fix build", "fix tests", "fix ci", "fix failure"),
            Yes to setOf("yes", "yeah", "yep", "sure", "confirm", "do", "do this"),
            No to setOf("no", "nope", "don't", "dont", "never mind", "nevermind", "not now"),
        )

        /** Returns the command [text] asks for, or null when it should go to Claude. */
        fun parse(text: String): VoiceCommand? {
            val words = text.lowercase().replace(Regex("[^a-z' ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty() || words.size > 6) return null
            val core = words.filterNot { it in fillers }.joinToString(" ")
            val whole = words.joinToString(" ")
            phrases.entries.firstOrNull { (_, set) -> core in set || whole in set }?.let { return it.key }
            // "Yes, do it", "sure, merge it": a yes followed by a couple of words still means yes.
            val first = core.substringBefore(' ')
            return if (core.count { it == ' ' } <= 2 && first in phrases.getValue(Yes)) Yes else null
        }
    }
}
