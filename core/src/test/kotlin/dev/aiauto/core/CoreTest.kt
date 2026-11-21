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

    @Test fun repoChoicesPutTheLastUsedFirst() {
        val saved = listOf(RepoRef("me", "app"), RepoRef("me", "site"))
        val available = listOf(RepoRef("me", "tools"), RepoRef("me", "site"), RepoRef("me", "notes"), RepoRef("me", "app"))
        fun task(id: String, repo: RepoRef, created: Long) =
            Task(id = id, repo = repo, title = id, branch = "b", createdMillis = created, updatedMillis = created)
        val tasks = listOf(task("1", RepoRef("me", "site"), 10), task("2", RepoRef("me", "notes"), 30), task("3", RepoRef("me", "site"), 20))
        assertEquals(
            listOf("me/notes", "me/site", "me/app", "me/tools"),
            RepoRef.choices(saved, available, tasks).map { it.fullName },
        )
    }

    @Test fun repoChoicesWorkWithoutTasksOrTheGitHubList() {
        val saved = listOf(RepoRef("me", "app"), RepoRef("me", "site"))
        assertEquals(saved, RepoRef.choices(saved, emptyList(), emptyList()))
        val available = listOf(RepoRef("me", "tools"), RepoRef("me", "app"))
        assertEquals(listOf("me/app", "me/site", "me/tools"), RepoRef.choices(saved, available, emptyList()).map { it.fullName })
    }

    @Test fun repoChoicesKeepTheCurrentCopyAndDropDuplicates() {
        val old = RepoRef("Me", "App", defaultBranch = "master")
        val current = RepoRef("me", "app", defaultBranch = "main")
        val task = Task(id = "1", repo = old, title = "t", branch = "b", createdMillis = 1, updatedMillis = 1)
        assertEquals(listOf(current), RepoRef.choices(emptyList(), listOf(current), listOf(task)))
        // A repo the token no longer lists still shows when a task used it.
        assertEquals(listOf(old), RepoRef.choices(emptyList(), emptyList(), listOf(task)))
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
