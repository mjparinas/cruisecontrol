package dev.aiauto.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The listening and thinking tones: their shape, loudness and when the thinking cue plays. */
class TonesTest {
    private fun peak(samples: ShortArray) = samples.maxOf { abs(it.toInt()) }

    @Test
    fun samplesLastAsLongAsTheNotes() {
        Tone.entries.forEach { tone ->
            assertEquals(tone.notes.sumOf { it.ms * Tone.SAMPLE_RATE / 1000 }, tone.samples().size, tone.name)
            assertEquals(tone.notes.sumOf { it.ms }.toLong(), tone.durationMs, tone.name)
        }
    }

    @Test
    fun eachNoteFadesInAndOutSoItDoesNotClick() {
        Tone.entries.forEach { tone ->
            val samples = tone.samples()
            var at = 0
            tone.notes.forEach { note ->
                val count = note.ms * Tone.SAMPLE_RATE / 1000
                assertEquals(0, samples[at].toInt(), "${tone.name} starts silent")
                assertEquals(0, samples[at + count - 1].toInt(), "${tone.name} ends silent")
                at += count
            }
        }
    }

    @Test
    fun notesPlayAtTheirPitch() {
        val note = Tone.Thinking.notes.single()
        val samples = Tone.Thinking.samples()
        val crossings = (1 until samples.size).count { (samples[it - 1] < 0) != (samples[it] < 0) }
        val expected = 2 * note.hz * note.ms / 1000
        assertTrue(abs(crossings - expected) <= 2, "expected about $expected zero crossings, got $crossings")
    }

    @Test
    fun startRisesAndStopFalls() {
        assertTrue(Tone.ListenStart.notes.first().hz < Tone.ListenStart.notes.last().hz)
        assertTrue(Tone.ListenStop.notes.first().hz > Tone.ListenStop.notes.last().hz)
    }

    @Test
    fun thinkingCueIsSofterThanTheMicrophoneTones() {
        val thinking = peak(Tone.Thinking.samples())
        assertTrue(thinking < peak(Tone.ListenStart.samples()))
        assertTrue(thinking < peak(Tone.ListenStop.samples()))
        Tone.entries.forEach { tone ->
            assertTrue(peak(tone.samples()) <= tone.notes.maxOf { it.volume } * Short.MAX_VALUE + 1, tone.name)
        }
    }

    @Test
    fun thinkingCuesStartAfterAFewSecondsAndStop() {
        val cues = Tone.thinkingCues()
        assertEquals(Tone.MAX_THINKING_CUES, cues.size)
        assertEquals(Tone.THINKING_AFTER_MS, cues.first())
        cues.zipWithNext().forEach { (a, b) -> assertEquals(Tone.THINKING_EVERY_MS, b - a) }
    }
}
