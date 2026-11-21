package dev.aiauto.core

/** A steering-wheel media button, as the app's media session reports it. */
enum class WheelButton { PlayPause, Next, Previous }

/** What a steering-wheel button does in the car. */
enum class WheelAction {
    /** Start listening on the car's Talk screen, as if Talk was tapped. */
    Talk,

    /** Say the last thing the app read aloud again. */
    Repeat,
}

/**
 * The experimental steering-wheel buttons: play or pause starts Talk, and next or previous repeats the last
 * update. Only used when Steering-wheel buttons is on in Settings.
 */
object SteeringWheel {
    const val NOTHING_TO_REPEAT = "There's nothing to repeat yet."

    fun actionFor(button: WheelButton): WheelAction = when (button) {
        WheelButton.PlayPause -> WheelAction.Talk
        WheelButton.Next, WheelButton.Previous -> WheelAction.Repeat
    }

    /** What to say for [WheelAction.Repeat], given the last thing read aloud. */
    fun repeatText(lastSaid: String?): String = lastSaid?.takeIf { it.isNotBlank() } ?: NOTHING_TO_REPEAT
}
