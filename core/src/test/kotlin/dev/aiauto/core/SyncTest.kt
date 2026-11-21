package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncTest {
    private val repo = RepoRef("me", "app")
    private fun msg(id: String, t: Long) = TaskMessage(id, Speaker.Claude, "m$id", t)
    private fun task(id: String, updated: Long, vararg messages: TaskMessage, device: String? = "phone") =
        Task(id, repo, "t$id", "b$id", createdMillis = 0, updatedMillis = updated, messages = messages.toList(), device = device)

    @Test fun newerTaskWinsAndKeepsEveryMessage() {
        val local = SyncSnapshot(tasks = listOf(task("a", 10, msg("1", 1), msg("2", 2)).copy(status = TaskStatus.Working)))
        val remote = SyncSnapshot(tasks = listOf(task("a", 20, msg("2", 2), msg("3", 3)).copy(status = TaskStatus.Done), task("b", 5)))
        val merged = SyncMerge.merge(local, remote)
        val a = merged.tasks.first { it.id == "a" }
        assertEquals(TaskStatus.Done, a.status)
        assertEquals(listOf("1", "2", "3"), a.messages.map { it.id })
        assertEquals(setOf("a", "b"), merged.tasks.map { it.id }.toSet())
    }

    @Test fun aTaskClearedOnEitherSideStaysCleared() {
        val local = SyncSnapshot(tasks = listOf(task("b", 5)), removedTasks = listOf("a"))
        val remote = SyncSnapshot(tasks = listOf(task("a", 20), task("b", 5)))
        val merged = SyncMerge.merge(local, remote)
        assertEquals(listOf("b"), merged.tasks.map { it.id })
        assertEquals(listOf("a"), merged.removedTasks)
        // The other way round too: the gist remembers a clear made on another device.
        assertEquals(listOf("b"), SyncMerge.merge(remote.copy(removedTasks = emptyList()), local).tasks.map { it.id })
    }

    @Test fun forgottenCarChatStaysForgotten() {
        val old = ChatExchange("hi", "hello", 10)
        val new = ChatExchange("again", "hey", 30)
        val local = SyncSnapshot(chat = emptyList(), chatForgottenMillis = 20)
        val remote = SyncSnapshot(chat = listOf(old, new))
        val merged = SyncMerge.merge(local, remote)
        assertEquals(listOf(new), merged.chat)
        assertEquals(20, merged.chatForgottenMillis)
    }

    @Test fun keepsOwnUnsentPrompt() {
        val local = SyncSnapshot(tasks = listOf(task("a", 10).copy(pendingPrompt = "go")))
        val remote = SyncSnapshot(tasks = listOf(task("a", 20)))
        assertEquals("go", SyncMerge.merge(local, remote).tasks.single().pendingPrompt)
        val takenOver = SyncSnapshot(tasks = listOf(task("a", 20, device = "tablet")))
        assertNull(SyncMerge.merge(local, takenOver).tasks.single().pendingPrompt)
    }

    @Test fun reposAreAUnionAndNewerSettingsWin() {
        val local = SyncSnapshot(repos = listOf(repo), settings = mapOf("x" to "1"), settingsChangedMillis = 5)
        val remote = SyncSnapshot(repos = listOf(RepoRef("Me", "App"), RepoRef("me", "other")), settings = mapOf("x" to "2"), settingsChangedMillis = 9)
        val merged = SyncMerge.merge(local, remote)
        assertEquals(listOf("me/app", "me/other"), merged.repos.map { it.fullName })
        assertEquals("2", merged.settings["x"])
        assertEquals(9, merged.settingsChangedMillis)
    }

    @Test fun aRepoRemovedOnOneDeviceIsRemovedOnTheOther() {
        val other = RepoRef("me", "other")
        val local = SyncSnapshot(repos = listOf(other), repoChanges = listOf(RepoChange("me/app", addedMillis = 0, removedMillis = 10)))
        val remote = SyncSnapshot(repos = listOf(repo, other))
        val merged = SyncMerge.merge(local, remote)
        assertEquals(listOf("me/other"), merged.repos.map { it.fullName })
        assertEquals(listOf(RepoChange("me/app", 0, 10)), merged.repoChanges)
        // The other way round too: the sync file remembers a removal made on another device.
        assertEquals(listOf("me/other"), SyncMerge.merge(remote, merged).repos.map { it.fullName })
    }

    @Test fun addingARepoAgainAfterRemovingItBringsItBack() {
        val removedHere = SyncSnapshot(repoChanges = listOf(RepoChange("me/app", removedMillis = 10)))
        val addedLaterThere = SyncSnapshot(repos = listOf(RepoRef("Me", "App")), repoChanges = listOf(RepoChange("Me/App", addedMillis = 20)))
        val merged = SyncMerge.merge(removedHere, addedLaterThere)
        assertEquals(listOf("Me/App"), merged.repos.map { it.fullName })
        assertEquals(20, merged.repoChanges.single().addedMillis)
        assertEquals(10, merged.repoChanges.single().removedMillis)
        assertEquals(listOf("Me/App"), SyncMerge.merge(addedLaterThere, removedHere).repos.map { it.fullName })
    }

    @Test fun aRepoAddedHereDuringASyncRoundIsKept() {
        // What RepoStore does when a round finishes: merge what came back with the store's current state.
        val (repos, _) = SyncMerge.mergeRepos(
            local = listOf(repo), localChanges = listOf(RepoChange("me/app", addedMillis = 30)),
            remote = emptyList(), remoteChanges = listOf(RepoChange("me/app", removedMillis = 20)),
        )
        assertEquals(listOf(repo), repos)
    }

    @Test fun uploadTrimsMessagesAndDropsUnsentPrompts() {
        val many = (1..100).map { msg("$it", it.toLong()) }.toTypedArray()
        val up = SyncMerge.forUpload(SyncSnapshot(tasks = listOf(task("a", 1, *many).copy(pendingPrompt = "x"))))
        assertEquals(SyncMerge.MESSAGES_PER_TASK, up.tasks.single().messages.size)
        assertEquals("100", up.tasks.single().messages.last().id)
        assertNull(up.tasks.single().pendingPrompt)
    }

    /** A GitHub account with the sync repo (once made) and any gists. */
    private class FakeAccount(var repoPrivate: Boolean? = null) : GitHub("t") {
        val sync = RepoRef("me", RepoSync.REPO)
        var file: RepoFile? = null
        var writes = 0
        var conflicts = 0
        val gists = mutableMapOf<String, String>()
        val deletedGists = mutableListOf<String>()
        override fun myLogin() = "me"
        override fun repoVisibility(owner: String, name: String) = repoPrivate?.let { sync to it }
        override fun createPrivateRepo(name: String, description: String): RepoRef { repoPrivate = true; return sync }
        override fun repoFile(repo: RepoRef, path: String) = file
        override fun putRepoFile(repo: RepoRef, path: String, content: String, sha: String?, message: String) {
            if (conflicts > 0) { conflicts--; throw GitHubException(409, "GitHub: conflict") }
            check(sha == file?.sha) { "stale sha" }
            writes++
            file = RepoFile(content, "sha$writes")
        }
        override fun myGists(perPage: Int) = gists.keys.map { Gist(it, files = mapOf(RepoSync.FILE to GistFile())) }
        override fun gistFile(id: String, name: String) = gists[id]
        override fun deleteGist(id: String) { gists.remove(id); deletedGists += id }
    }

    @Test fun makesAPrivateRepoThenMergesAndOnlyWritesChanges() {
        val account = FakeAccount()
        val sync = RepoSync(account)
        sync.sync(SyncSnapshot(repos = listOf(repo)))
        assertEquals(true, account.repoPrivate)
        val merged = sync.sync(SyncSnapshot(repos = listOf(RepoRef("me", "other")))).merged
        assertEquals(2, merged.repos.size)
        assertEquals(2, account.writes)
        sync.sync(merged)
        assertEquals(2, account.writes)
    }

    @Test fun refusesAPublicSyncRepo() {
        val account = FakeAccount(repoPrivate = false)
        assertFailsWith<SyncRepoNotPrivate> { RepoSync(account).sync(SyncSnapshot(repos = listOf(repo))) }
        assertNull(account.file)
    }

    @Test fun movesTheOldGistIntoTheRepoThenDeletesIt() {
        val account = FakeAccount()
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        account.gists["g1"] = json.encodeToString(SyncSnapshot.serializer(), SyncSnapshot(repos = listOf(RepoRef("me", "fromgist"))))
        val result = RepoSync(account).sync(SyncSnapshot(repos = listOf(repo)), checkGist = true)
        assertTrue(result.movedGist)
        assertEquals(listOf("me/app", "me/fromgist"), result.merged.repos.map { it.fullName })
        assertTrue("fromgist" in account.file!!.content)
        assertEquals(listOf("g1"), account.deletedGists)
    }

    @Test fun keepsTheGistWhenTheRepoWriteFails() {
        val account = FakeAccount()
        account.gists["g1"] = "{}"
        account.conflicts = 5
        assertFailsWith<GitHubException> { RepoSync(account).sync(SyncSnapshot(), checkGist = true) }
        assertEquals(emptyList(), account.deletedGists)
    }

    @Test fun triesAgainAfterAnotherDeviceWroteFirst() {
        val account = FakeAccount()
        RepoSync(account).sync(SyncSnapshot(repos = listOf(repo)))
        account.conflicts = 1
        RepoSync(account).sync(SyncSnapshot(repos = listOf(RepoRef("me", "other"))))
        assertEquals(2, account.writes)
    }
}

class SyncFirstDeviceTest {
    @Test fun aLaterDeviceWithDefaultsTakesTheFirstDevicesSettings() {
        var stored: GitHub.RepoFile? = null
        val account = object : GitHub("t") {
            override fun myLogin() = "me"
            override fun repoVisibility(owner: String, name: String) = RepoRef("me", name) to true
            override fun repoFile(repo: RepoRef, path: String) = stored
            override fun putRepoFile(repo: RepoRef, path: String, content: String, sha: String?, message: String) {
                stored = GitHub.RepoFile(content, "s")
            }
        }
        val sync = RepoSync(account)
        sync.sync(SyncSnapshot(settings = mapOf("theme_mode" to "s:Dark")))
        val merged = sync.sync(SyncSnapshot(settings = emptyMap())).merged
        assertEquals("s:Dark", merged.settings["theme_mode"])
    }
}
