package dev.aiauto.core

/**
 * What one go at voice input has heard so far, and whether to keep listening. The recognizer ends each stretch of
 * speech at a pause; with [untilStopped] on, listening starts again after every pause and the words are joined
 * until the driver taps Stop. With it off, the first pause ends it, as before. Until Stop, the recognizer's errors are
 * treated like pauses too, because it often fails while restarting after a pause or a stretch of silence; only
 * [MAX_FAILURES] errors in a row end it.
 */
class Dictation(private val untilStopped: Boolean) {
    enum class Next { Listen, Send, NothingHeard, Failed }

    private val parts = mutableListOf<String>()
    private var failures = 0

    /** Whether the driver has tapped Stop. */
    var stopped = false
        private set

    /** Everything heard so far, joined into one request. */
    val text: String get() = parts.joinToString(" ")

    /** The driver tapped Stop: the next pause sends what was heard. */
    fun stop() {
        stopped = true
    }

    /** The recognizer reached a pause, having heard [heard] (null or blank when nothing was said). */
    fun pause(heard: String?): Next {
        heard?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
        failures = 0
        return when {
            untilStopped && !stopped -> Next.Listen
            parts.isEmpty() -> Next.NothingHeard
            else -> Next.Send
        }
    }

    /**
     * Voice input broke. Until Stop it listens again, unless it keeps breaking. Otherwise it sends what was already
     * heard rather than dropping it.
     */
    fun broke(): Next {
        failures++
        return when {
            untilStopped && !stopped && failures < MAX_FAILURES -> Next.Listen
            parts.isEmpty() -> Next.Failed
            else -> Next.Send
        }
    }

    companion object {
        /** Errors in a row, with no pause heard between them, that end listening until Stop. */
        const val MAX_FAILURES = 5

        /** What was said, added after anything already typed in the phone's prompt box, as one request. */
        fun afterTyped(typed: String, spoken: String): String = "${typed.trim()} ${spoken.trim()}".trim()
    }
}
