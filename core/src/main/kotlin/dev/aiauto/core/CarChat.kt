package dev.aiauto.core

/**
 * The "Talk to Claude" conversation in Android Auto's messaging view. That view only lists conversations and has no
 * button for starting a new one, so this conversation is the one way to start a chat there. It stays in the list for
 * the whole drive: if it's dismissed, it comes straight back.
 */
object CarChat {
    const val GREETING = "Hi, it's Claude. Tap this conversation and say anything to start a chat. " +
        "To skip the Send button, turn on Unknown sources in Android Auto and use Talk on the Cruise Control screen."

    /** Whether a dismissed "Talk to Claude" conversation is put back, which only matters during a drive. */
    fun restoreAfterDismiss(inCar: Boolean, configured: Boolean): Boolean = inCar && configured
}
