package dev.aiauto.core

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorLogTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun entryKeepsTheTypeMessageAndTopOfTheTrace() {
        val error = IllegalStateException("Sync broke", java.io.IOException("timeout"))
        val entry = ErrorLog.entry(1_000L, "Sync failed", error)
        assertEquals("IllegalStateException: Sync broke", entry.message)
        assertTrue(entry.trace.first().startsWith("at "))
        assertTrue(entry.trace.any { it == "Caused by IOException: timeout" })
        assertTrue(entry.trace.size <= ErrorLog.TRACE_LINES + 4)
    }

    @Test
    fun anEntryWithoutAnErrorStillSaysSomething() {
        assertEquals("No details", ErrorLog.entry(0L, "Foreground time ran out", null).message)
    }

    @Test
    fun keysAndTokensAreHidden() {
        val entry = ErrorLog.entry(0L, "Calling GitHub", RuntimeException("Bad token ghp_abcdefghijklmnopqrstuvwxyz0123 and sk-ant-api03-secret"))
        assertFalse("ghp_" in entry.message)
        assertFalse("sk-ant" in entry.message)
        assertEquals("RuntimeException: Bad token [hidden] and [hidden]", entry.message)
    }

    @Test
    fun newestComesFirstAndOldOnesDropOff() {
        var list = emptyList<ErrorEntry>()
        repeat(ErrorLog.KEEP + 5) { list = ErrorLog.add(list, ErrorEntry(it.toLong(), "step $it", "boom")) }
        assertEquals(ErrorLog.KEEP, list.size)
        assertEquals("step ${ErrorLog.KEEP + 4}", list.first().where)
    }

    @Test
    fun reportListsEveryEntry() {
        val entries = listOf(
            ErrorEntry(1_764_079_500_000L, "Sync failed", "IOException: timeout", listOf("at a.b(C.kt:1)")),
            ErrorEntry(1_764_079_200_000L, "Couldn't update the widget", "No details"),
        )
        assertEquals(
            """
            Cruise Control 0.9.0, 2 recent errors

            Nov 25, 14:05  Sync failed
            IOException: timeout
                at a.b(C.kt:1)

            Nov 25, 14:00  Couldn't update the widget
            No details
            """.trimIndent(),
            ErrorLog.report(entries, "0.9.0", utc),
        )
    }
}
