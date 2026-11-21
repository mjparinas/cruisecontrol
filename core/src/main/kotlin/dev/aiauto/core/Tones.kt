package dev.aiauto.core

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Short sounds that let the driver keep their eyes on the road: a rising chirp when the microphone opens, a falling
 * one when it closes, and a soft low cue while an answer takes more than a few seconds. The app plays the samples
 * made here; the shapes and timing live in core so they can be tested.
 */
enum class Tone(val notes: List<Note>) {
    ListenStart(listOf(Note(660, 70, 0.5), Note(880, 90, 0.5))),
    ListenStop(listOf(Note(880, 70, 0.5), Note(660, 90, 0.5))),
    Thinking(listOf(Note(440, 120, 0.2))),
    ;

    /** One pitch held for [ms] milliseconds at [volume], from 0 to 1. */
    data class Note(val hz: Int, val ms: Int, val volume: Double)

    val durationMs: Long get() = notes.sumOf { it.ms }.toLong()

    /** The tone as 16-bit mono samples at [SAMPLE_RATE], each note faded in and out so it doesn't click. */
    fun samples(): ShortArray {
        val out = ShortArray(notes.sumOf { it.ms * SAMPLE_RATE / 1000 })
        var at = 0
        for (note in notes) {
            val count = note.ms * SAMPLE_RATE / 1000
            val fade = FADE_MS * SAMPLE_RATE / 1000
            for (i in 0 until count) {
                val envelope = min(1.0, min(i, count - 1 - i).toDouble() / fade)
                val wave = sin(2 * PI * note.hz * i / SAMPLE_RATE)
                out[at + i] = (wave * envelope * note.volume * Short.MAX_VALUE).roundToInt().toShort()
            }
            at += count
        }
        return out
    }

    companion object {
        const val SAMPLE_RATE = 22_050
        const val FADE_MS = 10

        /** How long an answer can take before the first [Thinking] cue, and the gap between cues after that. */
        const val THINKING_AFTER_MS = 3_000L
        const val THINKING_EVERY_MS = 5_000L

        /** Cues while waiting for one answer, so a slow answer doesn't chirp for the rest of the drive. */
        const val MAX_THINKING_CUES = 6

        /** When to play [Thinking] while waiting for an answer, in milliseconds after the question was sent. */
        fun thinkingCues(): List<Long> = List(MAX_THINKING_CUES) { THINKING_AFTER_MS + it * THINKING_EVERY_MS }
    }
}
