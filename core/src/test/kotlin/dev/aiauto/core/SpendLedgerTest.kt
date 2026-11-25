package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpendLedgerTest {
    private fun spend(device: String, day: String, dayUsd: Double, month: String = day.take(7), monthUsd: Double = dayUsd) =
        DeviceSpend(device, day, dayUsd, month, monthUsd)

    @Test
    fun mergeKeepsTheLargerTotalForTheSameDay() {
        val merged = SpendLedger.merge(
            listOf(spend("phone", "2026-11-25", 4.0, monthUsd = 30.0)),
            listOf(spend("phone", "2026-11-25", 6.0, monthUsd = 25.0)),
        )
        assertEquals(listOf(spend("phone", "2026-11-25", 6.0, monthUsd = 30.0)), merged)
    }

    @Test
    fun aLaterDayReplacesAnEarlierOne() {
        val merged = SpendLedger.merge(
            listOf(spend("tablet", "2026-11-24", 9.0, monthUsd = 40.0)),
            listOf(spend("tablet", "2026-11-25", 1.0, monthUsd = 41.0)),
        )
        assertEquals(listOf(spend("tablet", "2026-11-25", 1.0, monthUsd = 41.0)), merged)
    }

    @Test
    fun aNewMonthStartsItsTotalAgain() {
        val merged = SpendLedger.merge(
            listOf(spend("tablet", "2026-11-30", 5.0, monthUsd = 90.0)),
            listOf(spend("tablet", "2026-12-01", 2.0, monthUsd = 2.0)),
        )
        assertEquals(2.0, merged.single().monthUsd)
        assertEquals("2026-12", merged.single().month)
    }

    @Test
    fun eachDeviceKeepsItsOwnEntry() {
        val merged = SpendLedger.merge(
            listOf(spend("phone", "2026-11-25", 4.0)),
            listOf(spend("tablet", "2026-11-25", 3.0)),
        )
        assertEquals(setOf("phone", "tablet"), merged.map { it.device }.toSet())
    }

    @Test
    fun othersLeaveOutThisDeviceAndOtherDays() {
        val entries = listOf(
            spend("phone", "2026-11-25", 4.0, monthUsd = 10.0),
            spend("tablet", "2026-11-25", 3.0, monthUsd = 12.0),
            spend("old", "2026-11-20", 7.0, monthUsd = 8.0),
            spend("gone", "2026-10-30", 2.0, monthUsd = 50.0),
        )
        assertEquals(3.0, SpendLedger.othersOnDay(entries, "phone", "2026-11-25"))
        assertEquals(20.0, SpendLedger.othersInMonth(entries, "phone", "2026-11"))
    }

    @Test
    fun theListIsCappedToTheNewestDevices() {
        val many = (1..30).map { spend("d$it", "2026-11-%02d".format(it), 1.0) }
        val merged = SpendLedger.merge(many, emptyList())
        assertEquals(SpendLedger.DEVICES_KEPT, merged.size)
        assertTrue(merged.none { it.device == "d1" })
    }

    @Test
    fun syncMergeCombinesSpendFromBothSides() {
        val local = SyncSnapshot(spend = listOf(spend("phone", "2026-11-25", 4.0)))
        val remote = SyncSnapshot(spend = listOf(spend("tablet", "2026-11-25", 3.0), spend("phone", "2026-11-25", 2.0)))
        val merged = SyncMerge.merge(local, remote).spend.associateBy { it.device }
        assertEquals(4.0, merged.getValue("phone").dayUsd)
        assertEquals(3.0, merged.getValue("tablet").dayUsd)
    }

    @Test
    fun warnsOnceWhenADailyTotalPassesEightyPercent() {
        assertEquals(
            "Heads up: \$16.50 of today's \$20.00 limit is used. Claude stops when it's reached.",
            SpendWarning.after(15.0, 16.5, 20.0, 15.0, 16.5, null),
        )
        assertNull(SpendWarning.after(16.5, 17.0, 20.0, 16.5, 17.0, null), "Already past the warning point")
        assertNull(SpendWarning.after(10.0, 12.0, 20.0, 10.0, 12.0, null), "Not there yet")
        assertNull(SpendWarning.after(15.0, 21.0, 20.0, 15.0, 21.0, null), "The limit message covers this")
    }

    @Test
    fun theMonthlyWarningComesFirst() {
        val text = SpendWarning.after(15.0, 17.0, 20.0, 75.0, 85.0, 100.0)
        assertEquals("Heads up: \$85.00 of this month's \$100.00 limit is used. Claude stops when it's reached.", text)
    }

    @Test
    fun noWarningWithoutALimit() {
        assertNull(SpendWarning.after(0.0, 100.0, null, 0.0, 100.0, null))
    }
}
