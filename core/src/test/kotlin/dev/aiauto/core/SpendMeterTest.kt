package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpendMeterTest {
    @Test fun showsTheAmountAndLimit() {
        val meter = SpendMeter("Today", 4.5, 20.0)
        assertEquals("$4.50", meter.amount)
        assertEquals("of $20.00", meter.note)
        assertEquals(0.225f, meter.share)
        assertEquals("Today, $4.50 of $20.00", meter.description)
    }

    @Test fun saysWhenThereIsNoLimit() {
        val meter = SpendMeter("This month", 12.0, null)
        assertEquals("No limit", meter.note)
        assertNull(meter.share)
        assertEquals(SpendLevel.Fine, meter.level)
        assertEquals("This month, $12.00 with no limit", meter.description)
    }

    @Test fun warnsNearTheLimitAndStopsTheBarAtFull() {
        assertEquals(SpendLevel.Fine, SpendMeter("Today", 15.9, 20.0).level)
        assertEquals(SpendLevel.Near, SpendMeter("Today", 16.0, 20.0).level)
        assertEquals(SpendLevel.Reached, SpendMeter("Today", 20.0, 20.0).level)
        assertEquals(1f, SpendMeter("Today", 25.0, 20.0).share)
    }
}
