package dev.aiauto.core

/**
 * Keeps lines that would be read aloud while a microphone is listening, so the car doesn't talk over the driver or
 * hear itself. Every [hold] needs a matching [release]; once the last one is released, the held lines come back
 * oldest first. When more than [maxHeld] pile up, the oldest are dropped. Not thread-safe: the caller synchronizes.
 */
class SpeechHold<T>(private val maxHeld: Int = 5) {
    private var holders = 0
    private val held = ArrayDeque<T>()

    val isHeld: Boolean get() = holders > 0

    fun hold() {
        holders++
    }

    /** Keeps [line] and returns true while held; returns false when it can be spoken now. */
    fun offer(line: T): Boolean {
        if (!isHeld) return false
        if (held.size >= maxHeld) held.removeFirst()
        held.addLast(line)
        return true
    }

    /** Ends one hold. Returns the held lines once nothing is holding any more, and nothing before that. */
    fun release(): List<T> {
        if (holders > 0) holders--
        if (isHeld) return emptyList()
        return held.toList().also { held.clear() }
    }

    /** Drops the held lines without ending the hold. */
    fun clear() = held.clear()
}
