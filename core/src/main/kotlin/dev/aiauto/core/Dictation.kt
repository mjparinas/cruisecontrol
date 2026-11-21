package dev.aiauto.core

/**
 * What one go at voice input has heard so far, and whether to keep listening. The recognizer ends each stretch of
 * speech at a pause; with [untilStopped] on, listening starts again after every pause and the words are joined
 * until the driver taps Stop. With it off, the first pause ends it, as before.
 */
class Dictation(private val untilStopped: Boolean) {
    enum class Next { Listen, Send, NothingHeard, Failed }

    private val parts = mutableListOf<String>()
    private var stopped = false

    /** Everything heard so far, joined into one request. */
    val text: String get() = parts.joinToString(" ")

    /** The driver tapped Stop: the next pause sends what was heard. */
    fun stop() {
        stopped = true
    }

    /** The recognizer reached a pause, having heard [heard] (null or blank when nothing was said). */
    fun pause(heard: String?): Next {
        heard?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
        return when {
            untilStopped && !stopped -> Next.Listen
            parts.isEmpty() -> Next.NothingHeard
            else -> Next.Send
        }
    }

    /** Voice input broke. Sends what was already heard rather than dropping it. */
    fun broke(): Next = if (parts.isEmpty()) Next.Failed else Next.Send

    companion object {
        /** What was said, added after anything already typed in the phone's prompt box, as one request. */
        fun afterTyped(typed: String, spoken: String): String = "${typed.trim()} ${spoken.trim()}".trim()
    }
}
