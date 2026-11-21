package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VoiceShortcutTest {
    private val aiauto = RepoRef("mjparinas", "aiauto")
    private val site = RepoRef("mjparinas", "cruise-control-site")
    private val repos = listOf(aiauto, site)

    private fun start(spoken: String, lastUsed: RepoRef? = null, all: List<RepoRef> = repos) =
        assertIs<VoiceShortcut.Start>(VoiceShortcut.resolve(spoken, all, lastUsed, TaskTemplate.defaults, configured = true))

    private fun problem(spoken: String, all: List<RepoRef> = repos, configured: Boolean = true) =
        assertIs<VoiceShortcut.Problem>(VoiceShortcut.resolve(spoken, all, null, TaskTemplate.defaults, configured))

    @Test fun repoNamedAtTheEnd() {
        val s = start("add dark mode to aiauto")
        assertEquals(aiauto, s.repo)
        assertEquals("add dark mode", s.request)
        assertEquals("Starting on aiauto: add dark mode.", s.spoken)
    }

    @Test fun repoNamedAtTheStart() {
        assertEquals(aiauto to "add a settings toggle", start("in aiauto, add a settings toggle").let { it.repo to it.request })
        assertEquals(aiauto to "add a settings toggle", start("In aiauto add a settings toggle.").let { it.repo to it.request })
    }

    @Test fun spokenRepoNamesWithSeveralWords() {
        val s = start("fix the footer in the cruise control site repo")
        assertEquals(site, s.repo)
        assertEquals("fix the footer", s.request)
    }

    @Test fun keepsPrepositionsThatAreNotARepo() {
        val s = start("add a link to the docs in aiauto")
        assertEquals(aiauto, s.repo)
        assertEquals("add a link to the docs", s.request)
    }

    @Test fun fallsBackToTheLastUsedRepo() {
        val s = start("add a link to the docs", lastUsed = site)
        assertEquals(site, s.repo)
        assertEquals("add a link to the docs", s.request)
    }

    @Test fun fallsBackToTheOnlyRepo() {
        assertEquals(aiauto, start("bump the version", all = listOf(aiauto)).repo)
    }

    @Test fun lastUsedRepoMustStillBeSaved() {
        problem("bump the version").also { assertEquals("Which repo is that for? Say it at the end, like add dark mode to aiauto.", it.spoken) }
        val removed = RepoRef("someone", "old")
        assertIs<VoiceShortcut.Problem>(VoiceShortcut.resolve("bump the version", repos, removed, emptyList(), configured = true))
    }

    @Test fun templateNamesStartTheTemplate() {
        val s = start("fix lint in aiauto")
        assertEquals(TaskTemplate.defaults.first { it.id == "fix-lint" }.prompt, s.request)
        assertEquals("Starting Fix lint on aiauto.", s.spoken)
    }

    @Test fun longerRequestsMentioningATemplateAreSentAsSaid() {
        assertEquals("add missing tests for the parser", start("add missing tests for the parser in aiauto").request)
    }

    @Test fun dropsALeadingTo() {
        assertEquals("add dark mode", start("to add dark mode to aiauto").request)
    }

    @Test fun explainsWhatIsMissing() {
        assertEquals("Add your Claude key and GitHub token in Cruise Control first.", problem("add dark mode", configured = false).spoken)
        assertEquals("Add a repo in Cruise Control first.", problem("add dark mode", all = emptyList()).spoken)
        assertEquals("I didn't catch what you want Claude to do. Try again, like add dark mode to aiauto.", problem("  ").spoken)
        assertEquals("I didn't catch what you want Claude to do in aiauto.", problem("in aiauto").spoken)
    }
}
