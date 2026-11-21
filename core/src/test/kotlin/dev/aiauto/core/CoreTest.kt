package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    @Test fun parsesRepoText() {
        assertEquals(RepoRef("mjparinas", "aiauto"), RepoRef.parse("mjparinas/aiauto"))
        assertEquals(RepoRef("a", "b.c"), RepoRef.parse("https://github.com/a/b.c.git"))
        assertNull(RepoRef.parse("not a repo"))
        assertNull(RepoRef.parse("../.."))
        assertNull(RepoRef.parse("owner/.."))
    }

    @Test fun repoNameLeavesOutTheOwner() {
        val repo = RepoRef("mjparinas", "aiauto")
        assertEquals("aiauto", repo.name)
        assertEquals("mjparinas/aiauto", repo.fullName)
    }

    @Test fun matchesSpokenRepoNames() {
        val mine = listOf(RepoRef("mjparinas", "aiauto"), RepoRef("mjparinas", "my-site"), RepoRef("other", "my_site"))
        assertEquals(listOf(mine[0]), RepoRef.matchSpoken("AI auto", mine))
        assertEquals(listOf(mine[0]), RepoRef.matchSpoken("mjparinas/aiauto", mine))
        assertEquals(listOf(mine[1]), RepoRef.matchSpoken("mjparinas slash my site", mine))
        assertEquals(mine.drop(1), RepoRef.matchSpoken("my site", mine))
        assertTrue(RepoRef.matchSpoken("nothing", mine).isEmpty())
        assertTrue(RepoRef.matchSpoken("  ", mine).isEmpty())
    }

    @Test fun cleansMarkdownForSpeech() {
        val text = "## Done\n- Updated **README** with `make test`\n```kotlin\nfun x() {}\n```\nSee [PR](https://x)."
        assertEquals("Done Updated README with make test See PR.", SpokenStatus.clean(text))
    }

    @Test fun trimsLongMessagesAtASentence() {
        val first = "I updated the settings screen and added a dark mode toggle that follows the system."
        assertEquals(first, SpokenStatus.clean(first + " " + "More detail here. ".repeat(20)).substringBefore(" More"))
        assertTrue(SpokenStatus.clean("x ".repeat(300)).length <= 221)
    }

    @Test fun describesCommands() {
        assertEquals("Pushing changes to GitHub", SpokenStatus.forToolUse("bash", "git push -u origin x"))
        assertEquals("Building or testing", SpokenStatus.forToolUse("bash", "npm test"))
        assertEquals("Editing files", SpokenStatus.forToolUse("edit", null))
    }

    @Test fun putsTasksThatNeedYouFirst() {
        fun task(id: String, status: TaskStatus) = Task(id, RepoRef("a", "b"), id, id, status = status, createdMillis = 0, updatedMillis = 0)
        val tasks = listOf(task("1", TaskStatus.Working), task("2", TaskStatus.NeedsInput), task("3", TaskStatus.Done), task("4", TaskStatus.NeedsInput))
        assertEquals(listOf("2", "4", "1", "3"), tasks.needsYouFirst().map { it.id })
    }

    @Test fun makesTitlesAndBranches() {
        assertEquals("Add a dark mode toggle to settings", TaskEngine.titleFrom("add a dark mode toggle to settings"))
        assertEquals("add-a-dark-mode-toggle", TaskEngine.slug("Add a dark-mode toggle, to settings!"))
        assertEquals("task", TaskEngine.slug("!!!"))
    }
}
