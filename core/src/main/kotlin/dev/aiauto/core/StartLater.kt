package dev.aiauto.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Times a task can be saved for, and how they're said. Tasks for later wait on the phone and start on their own, so
 * a request made on the drive home can run overnight instead of right away.
 */
object StartLater {
    /** The evening start offered as "Tonight". */
    val EVENING: LocalTime = LocalTime.of(20, 0)

    /** The morning start offered as "Tomorrow morning". */
    val MORNING: LocalTime = LocalTime.of(8, 0)

    /** The longest a task can wait, so a spoken slip can't park it for weeks. */
    const val MAX_WAIT_MINUTES = 7 * 24 * 60

    /** One choice on the phone's Start later list. */
    data class Option(val label: String, val atMillis: Long)

    /** In an hour, tonight at 8 PM (or tomorrow night once that's passed) and tomorrow at 8 AM. */
    fun options(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): List<Option> {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val inAnHour = now.plusHours(1).truncatedTo(ChronoUnit.MINUTES)
        val evening = now.with(EVENING).let { if (it.isAfter(now.plusMinutes(30))) it else it.plusDays(1) }
        val morning = now.plusDays(1).with(MORNING)
        return listOf(
            Option("In an hour", inAnHour.toInstant().toEpochMilli()),
            Option(if (evening.toLocalDate() == now.toLocalDate()) "Tonight at 8 PM" else "Tomorrow at 8 PM", evening.toInstant().toEpochMilli()),
            Option("Tomorrow at 8 AM", morning.toInstant().toEpochMilli()),
        )
    }

    /** When the car chat asks for a task in [minutes]: clamped to between 1 minute and [MAX_WAIT_MINUTES]. */
    fun inMinutes(nowMillis: Long, minutes: Int): Long =
        nowMillis + minutes.coerceIn(1, MAX_WAIT_MINUTES) * 60_000L

    /** How a start time is said: "at 8:00 PM tonight", "at 8:00 AM tomorrow" or "at 9:30 PM on Friday, November 27". */
    fun spoken(atMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(atMillis), zone)
        val today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone).toLocalDate()
        val time = DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(at)
        return when (at.toLocalDate()) {
            today -> "at $time today"
            today.plusDays(1) -> "at $time tomorrow"
            else -> "at $time on " + DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US).format(at)
        }
    }
}
