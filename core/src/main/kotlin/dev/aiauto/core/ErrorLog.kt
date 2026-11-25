package dev.aiauto.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One error the app caught: when, what it was doing, what went wrong, and the top of the stack trace. */
@Serializable
data class ErrorEntry(
    val timeMillis: Long,
    val where: String,
    val message: String,
    val trace: List<String> = emptyList(),
)

/**
 * The recent errors kept on the phone, so a failure in the car can be looked at and copied into a bug report later.
 * Android's own log isn't readable on the phone, and it's gone after a restart. Nothing here is sent anywhere.
 */
object ErrorLog {
    /** Errors kept. Older ones drop off. */
    const val KEEP = 50

    /** Stack trace lines kept per error: enough to find the spot, small enough to read. */
    const val TRACE_LINES = 8

    private val secrets = listOf(
        Regex("sk-ant-[A-Za-z0-9_-]+"),
        Regex("sk-[A-Za-z0-9_-]{20,}"),
        Regex("AIza[A-Za-z0-9_-]{20,}"),
        Regex("gh[pousr]_[A-Za-z0-9]{20,}"),
        Regex("github_pat_[A-Za-z0-9_]{20,}"),
        Regex("(?i)(bearer|token)\\s+[A-Za-z0-9._-]{20,}"),
    )

    /** An entry for [error] while doing [where]. Anything that looks like a key or token is blanked out. */
    fun entry(timeMillis: Long, where: String, error: Throwable?): ErrorEntry {
        val message = error?.let { e ->
            val text = e.message?.takeIf { it.isNotBlank() }
            listOfNotNull(e.javaClass.simpleName.ifEmpty { "Error" }, text).joinToString(": ")
        } ?: "No details"
        val trace = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(3).flatMapIndexed { i, e ->
            val head = if (i == 0) emptyList() else listOf("Caused by ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
            head + e.stackTrace.take(if (i == 0) TRACE_LINES else 3).map { "at $it" }
        }.toList()
        return ErrorEntry(timeMillis, redact(where), redact(message), trace.map(::redact))
    }

    /** [entries] with [entry] added at the front, keeping the newest [KEEP]. */
    fun add(entries: List<ErrorEntry>, entry: ErrorEntry): List<ErrorEntry> = (listOf(entry) + entries).take(KEEP)

    /** Plain text of every entry, newest first, for copying into a bug report. */
    fun report(entries: List<ErrorEntry>, version: String, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append("Cruise Control $version, ${entries.size} recent error${if (entries.size == 1) "" else "s"}\n")
        entries.forEach { e ->
            append('\n').append(time(e.timeMillis, zone)).append("  ").append(e.where).append('\n')
            append(e.message).append('\n')
            e.trace.forEach { append("    ").append(it).append('\n') }
        }
    }.trimEnd()

    /** A short time for the list, like "Nov 25, 14:05". */
    fun time(timeMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("MMM d, HH:mm", java.util.Locale.ENGLISH).withZone(zone).format(Instant.ofEpochMilli(timeMillis))

    fun redact(text: String): String = secrets.fold(text) { acc, regex -> regex.replace(acc, "[hidden]") }
}
