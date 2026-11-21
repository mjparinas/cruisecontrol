package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        assertEquals(Dictation.Next.Failed, Dictation(untilStopped = false).broke())
        val d = Dictation(untilStopped = false)
        d.pause("fix the login bug")
        assertEquals(Dictation.Next.Send, d.broke())
        assertEquals("fix the login bug", d.text)
    }

    @Test
    fun errorsBeforeAnyWordsKeepListeningUntilStopped() {
        val d = Dictation(untilStopped = true)
        assertEquals(Dictation.Next.Listen, d.broke())
        assertEquals(Dictation.Next.Listen, d.pause(null))
        assertEquals(Dictation.Next.Listen, d.broke())
        assertEquals(Dictation.Next.Listen, d.pause("add dark mode"))
    }

    @Test
    fun anErrorAfterAPauseDoesNotSendUntilStopped() {
        val d = Dictation(untilStopped = true)
        d.pause("fix the login bug")
        assertEquals(Dictation.Next.Listen, d.broke())
        assertEquals(Dictation.Next.Listen, d.pause("on the phone"))
        d.stop()
        assertEquals(Dictation.Next.Send, d.pause(null))
        assertEquals("fix the login bug on the phone", d.text)
    }

    @Test
    fun anErrorAfterStopSendsWhatWasHeard() {
        val d = Dictation(untilStopped = true)
        d.pause("fix the login bug")
        d.stop()
        assertTrue(d.stopped)
        assertEquals(Dictation.Next.Send, d.broke())
    }

    @Test
    fun errorsInARowEndListeningUntilStopped() {
        val d = Dictation(untilStopped = true)
        repeat(Dictation.MAX_FAILURES - 1) { assertEquals(Dictation.Next.Listen, d.broke()) }
        assertEquals(Dictation.Next.Failed, d.broke())
        val heard = Dictation(untilStopped = true)
        heard.pause("rename the button")
        repeat(Dictation.MAX_FAILURES - 1) { assertEquals(Dictation.Next.Listen, heard.broke()) }
        assertEquals(Dictation.Next.Send, heard.broke())
        assertEquals("rename the button", heard.text)
    }

    @Test
    fun spokenWordsFollowWhatWasTyped() {
        assertEquals("add dark mode", Dictation.afterTyped("", "add dark mode"))
        assertEquals("add dark mode", Dictation.afterTyped("  ", " add dark mode "))
        assertEquals("in the settings add dark mode", Dictation.afterTyped("in the settings ", "add dark mode"))
    }
}
