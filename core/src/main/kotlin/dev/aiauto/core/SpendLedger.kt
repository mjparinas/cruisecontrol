package dev.aiauto.core

import kotlinx.serialization.Serializable

/**
 * What one device has spent on its own: today's total for [day] and this month's for [month], by that device's local
 * date (a day like 2026-11-25 and a month like 2026-11). Each device only ever adds to its own entry, so merging
 * keeps the larger total for the same day, and the spending limits can add every device's entry together.
 */
@Serializable
data class DeviceSpend(
    val device: String,
    val day: String,
    val dayUsd: Double,
    val month: String,
    val monthUsd: Double,
)

/** Combines the devices' own spending so the daily and monthly limits hold across every device that syncs. */
object SpendLedger {
    /** Devices kept in the synced list. Old phones drop off once the list is longer. */
    const val DEVICES_KEPT = 20

    /**
     * One entry per device: the one for the later day, or for the same day the larger totals. Totals only grow within
     * a day and a month, so taking the larger never loses spend and never counts it twice.
     */
    fun merge(a: List<DeviceSpend>, b: List<DeviceSpend>): List<DeviceSpend> =
        (a + b).groupBy { it.device }.map { (_, same) -> same.reduce(::newer) }
            .sortedByDescending { it.day }
            .take(DEVICES_KEPT)

    /** What every device except [me] spent on [day]. */
    fun othersOnDay(entries: List<DeviceSpend>, me: String, day: String): Double =
        entries.filter { it.device != me && it.day == day }.sumOf { it.dayUsd }

    /** What every device except [me] spent in [month]. */
    fun othersInMonth(entries: List<DeviceSpend>, me: String, month: String): Double =
        entries.filter { it.device != me && it.month == month }.sumOf { it.monthUsd }

    private fun newer(x: DeviceSpend, y: DeviceSpend): DeviceSpend {
        val dayUsd = when {
            x.day > y.day -> x.dayUsd
            y.day > x.day -> y.dayUsd
            else -> maxOf(x.dayUsd, y.dayUsd)
        }
        val (month, monthUsd) = when {
            x.month > y.month -> x.month to x.monthUsd
            y.month > x.month -> y.month to y.monthUsd
            else -> x.month to maxOf(x.monthUsd, y.monthUsd)
        }
        return DeviceSpend(x.device, maxOf(x.day, y.day), dayUsd, month, monthUsd)
    }
}

/**
 * The one-off spoken warning when spending passes [SpendMeter.NEAR_SHARE] of a limit, so the driver hears it coming
 * before Claude stops instead of being cut off mid-task.
 */
object SpendWarning {
    /**
     * The sentence to say when this spend took a total past the warning point, or null when it didn't. The month
     * comes first, like the limit message, since raising today's limit wouldn't help once the month is nearly used.
     */
    fun after(
        dayBefore: Double,
        dayAfter: Double,
        dailyLimit: Double?,
        monthBefore: Double,
        monthAfter: Double,
        monthlyLimit: Double?,
    ): String? = when {
        crossed(monthBefore, monthAfter, monthlyLimit) -> say("this month's", monthAfter, monthlyLimit!!)
        crossed(dayBefore, dayAfter, dailyLimit) -> say("today's", dayAfter, dailyLimit!!)
        else -> null
    }

    /** True when [after] is at or past the warning point and [before] wasn't, and the limit isn't already reached. */
    fun crossed(before: Double, after: Double, limit: Double?): Boolean {
        if (limit == null || limit <= 0) return false
        val point = limit * SpendMeter.NEAR_SHARE
        return before < point && after >= point && after < limit
    }

    private fun say(which: String, spent: Double, limit: Double): String =
        "Heads up: ${SpokenStatus.money(spent)} of $which ${SpokenStatus.money(limit)} limit is used. " +
            "Claude stops when it's reached."
}
