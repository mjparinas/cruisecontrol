package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechHoldTest {
    @Test
    fun speaksRightAwayWhenNothingIsListening() {
        val hold = SpeechHold<String>()
        assertFalse(hold.offer("Tests passed"))
        assertEquals(emptyList(), hold.release())
    }

    @Test
    fun keepsLinesWhileListeningAndHandsThemBackInOrder() {
        val hold = SpeechHold<String>()
        hold.hold()
        assertTrue(hold.offer("Pushing changes"))
        assertTrue(hold.offer("PR 5 opened"))
        assertEquals(listOf("Pushing changes", "PR 5 opened"), hold.release())
        assertFalse(hold.isHeld)
        assertFalse(hold.offer("Checks passing"))
    }

    @Test
    fun waitsForEveryMicrophoneToStop() {
        val hold = SpeechHold<String>()
        hold.hold()
        hold.hold()
        hold.offer("Done")
        assertEquals(emptyList(), hold.release())
        assertTrue(hold.isHeld)
        assertEquals(listOf("Done"), hold.release())
    }

    @Test
    fun dropsTheOldestWhenFull() {
        val hold = SpeechHold<String>(maxHeld = 2)
        hold.hold()
        listOf("one", "two", "three").forEach { hold.offer(it) }
        assertEquals(listOf("two", "three"), hold.release())
    }

    @Test
    fun clearDropsLinesButKeepsHolding() {
        val hold = SpeechHold<String>()
        hold.hold()
        hold.offer("old news")
        hold.clear()
        assertTrue(hold.isHeld)
        assertTrue(hold.offer("fresh"))
        assertEquals(listOf("fresh"), hold.release())
    }

    @Test
    fun extraReleasesAreHarmless() {
        val hold = SpeechHold<String>()
        assertEquals(emptyList(), hold.release())
        hold.hold()
        assertTrue(hold.offer("still held"))
    }
}
