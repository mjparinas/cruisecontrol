package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Voice input that stops at the first pause, or keeps going until the driver taps Stop. */
class DictationTest {
    @Test
    fun firstPauseSendsWhenNotListeningUntilStopped() {
        val d = Dictation(untilStopped = false)
        assertEquals(Dictation.Next.Send, d.pause("add dark mode"))
        assertEquals("add dark mode", d.text)
    }

    @Test
    fun silenceWithoutWordsIsNothingHeard() {
        assertEquals(Dictation.Next.NothingHeard, Dictation(untilStopped = false).pause(null))
        assertEquals(Dictation.Next.NothingHeard, Dictation(untilStopped = false).pause("  "))
    }

    @Test
    fun keepsListeningAcrossPausesUntilStopped() {
        val d = Dictation(untilStopped = true)
        assertEquals(Dictation.Next.Listen, d.pause("add a settings switch"))
        assertEquals(Dictation.Next.Listen, d.pause(null))
        assertEquals(Dictation.Next.Listen, d.pause(" for quiet mode "))
        d.stop()
        assertEquals(Dictation.Next.Send, d.pause("and test it"))
        assertEquals("add a settings switch for quiet mode and test it", d.text)
    }

    @Test
    fun stopAfterAPauseStillSendsEarlierWords() {
        val d = Dictation(untilStopped = true)
        d.pause("rename the button")
        d.stop()
        assertEquals(Dictation.Next.Send, d.pause(null))
        assertEquals("rename the button", d.text)
    }

    @Test
    fun stopWithNothingSaidIsNothingHeard() {
        val d = Dictation(untilStopped = true)
        d.stop()
        assertEquals(Dictation.Next.NothingHeard, d.pause(null))
    }

    @Test
    fun aBreakKeepsWhatWasHeard() {
        assertEquals(Dictation.Next.Failed, Dictation(untilStopped = true).broke())
        val d = Dictation(untilStopped = true)
        d.pause("fix the login bug")
        assertEquals(Dictation.Next.Send, d.broke())
        assertEquals("fix the login bug", d.text)
    }
}
