package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlanceTest {
    @Test fun keepsShortTextAsIs() {
        assertEquals("Opened PR 5.", SpokenStatus.glance("  Opened PR 5.  "))
    }

    @Test fun keepsWholeSentencesThatFit() {
        val text = "I fixed the login bug and added a test. " + "Then I updated the docs to match the new flow and pushed. ".repeat(3)
        assertEquals("I fixed the login bug and added a test.", SpokenStatus.glance(text, max = 60))
    }

    @Test fun cutsALongSentenceAtAWord() {
        val out = SpokenStatus.glance("word ".repeat(60), max = 50)
        assertTrue(out.endsWith("word…"), out)
        assertTrue(out.length <= 50, out)
    }

    @Test fun cutsTextWithNoSpaces() {
        val out = SpokenStatus.glance("x".repeat(200), max = 40)
        assertEquals(40, out.length)
        assertTrue(out.endsWith("…"))
    }
}
