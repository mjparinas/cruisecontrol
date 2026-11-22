package dev.aiauto.core

/** How close a spending total is to its limit, which sets the colour of its bar on the home screen. */
enum class SpendLevel { Fine, Near, Reached }

/** One spending total on the home screen, today's or this month's, with its limit when one is set. */
data class SpendMeter(val label: String, val spent: Double, val limit: Double?) {
    val amount: String get() = SpokenStatus.money(spent)

    /** The line under the amount: the limit, or that there is none. */
    val note: String get() = limit?.let { "of ${SpokenStatus.money(it)}" } ?: "No limit"

    /** How much of the limit is used, from 0 to 1, or null with no limit. */
    val share: Float? get() = limit?.takeIf { it > 0 }?.let { (spent / it).coerceIn(0.0, 1.0).toFloat() }

    val level: SpendLevel get() = when {
        limit == null || limit <= 0 -> SpendLevel.Fine
        spent >= limit -> SpendLevel.Reached
        spent >= limit * NEAR_SHARE -> SpendLevel.Near
        else -> SpendLevel.Fine
    }

    /** What a screen reader says for the whole meter. */
    val description: String get() = "$label, $amount " + (limit?.let { "of ${SpokenStatus.money(it)}" } ?: "with no limit")

    companion object {
        /** From 80% of the limit the bar turns amber, as a warning before Claude stops. */
        const val NEAR_SHARE = 0.8
    }
}
