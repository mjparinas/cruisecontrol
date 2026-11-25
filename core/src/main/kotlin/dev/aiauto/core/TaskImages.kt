package dev.aiauto.core

import kotlinx.serialization.Serializable

/** A picture sent with a task's first prompt, such as a screenshot shared from another app. [base64] holds its bytes. */
@Serializable
data class TaskImage(val mediaType: String, val base64: String)

/** Limits and sizing for pictures shared into a task, kept here so they're tested. */
object TaskImages {
    /** Pictures per task. More than this mostly adds cost. */
    const val MAX = 4

    /** The longest side Claude looks at; bigger pictures are scaled down to it before sending. */
    const val MAX_SIDE = 1568

    /** The most one picture may take once encoded, under the API's 5 MB per image. */
    const val MAX_BYTES = 3_750_000

    /** The power of two to shrink by while decoding, so a huge photo never fills memory. */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    /** The size to scale a picture to so its longest side is at most [maxSide], keeping its shape. */
    fun scaledSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= maxSide || longest <= 0) return width to height
        val scale = maxSide.toDouble() / longest
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    /** "1 screenshot" or "3 screenshots", for the screen and for reading back. */
    fun describe(count: Int): String = if (count == 1) "1 screenshot" else "$count screenshots"
}
