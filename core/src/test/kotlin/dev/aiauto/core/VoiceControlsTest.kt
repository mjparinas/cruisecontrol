package dev.aiauto.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Voice status, stop and merge: finding what the driver means, and never merging without a later yes. */
class VoiceControlsTest {
    private val app = RepoRef("me", "app")
    private val site = RepoRef("me", "site")

    private class MapStore : TaskStore {
        val map = LinkedHashMap<String, Task>()
        override fun get(id: String) = map[id]
        override fun all() = map.values.toList()
        override fun put(task: Task) { map[task.id] = task }
        override fun remove(id: String) { map.remove(id) }
    }

    private class Config : EngineConfig {
        override fun anthropicKey(): String? = null
        override fun githubToken(): String? = null
        override fun agentSetup(): AgentSetup? = null
        override fun saveAgentSetup(setup: AgentSetup) {}
    }

    private class FakeGitHub : GitHub("token") {
        var pull = PullRequest(42, "https://github.com/me/app/pull/42", title = "Fix login", head = PullRequest.Head("abc"))
        var checksResult = ChecksSummary(Checks.Passing, total = 2)
        val stateChanges = mutableListOf<Pair<Int, Boolean>>()
        override fun pull(repo: RepoRef, number: Int) = pull.copy(number = number)
        override fun checks(repo: RepoRef, sha: String) = checksResult
        override fun setPullState(repo: RepoRef, number: Int, open: Boolean): PullRequest {
            stateChanges += number to open
            pull = pull.copy(
                number = number,
                state = if (open) "open" else "closed",
                merged = false,
            )
            return pull
        }
    }

    private val store = MapStore()
    private val github = FakeGitHub()
    private val merged = mutableListOf<Pair<String, Int>>()
    private val engine = TaskEngine(store, Config(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), dispatcher = Dispatchers.Unconfined)
    private var saved = listOf(app, site)
    private val controls = VoiceControls(store, { saved }, engine, { github }) { repo, n -> merged += repo.fullName to n; "Merged pull request $n." }

    private fun task(id: String, repo: RepoRef, status: TaskStatus = TaskStatus.Done, pr: Int? = null) =
        Task(id, repo, "Task $id", "claude/$id", status = status, prNumber = pr, prState = pr?.let { PrState.Open }, createdMillis = 0, updatedMillis = 0)
            .also(store::put)

    @Test
    fun pullRequestStatusReadsChecks() {
        github.checksResult = ChecksSummary(Checks.Failing, listOf("build"), 2)
        val said = controls.status("app", 42, "")
        assertTrue("Pull request 42" in said && "Build failing" in said, said)
    }

    @Test
    fun repoComesFromTheTaskThatOwnsThePullRequest() {
        task("t1", site, pr = 9)
        val preview = controls.previewMerge("", 9)
        assertEquals(site, preview.repo)
    }

    @Test
    fun unclearRepoAsksWhichOne() {
        val e = assertFailsWith<IllegalArgumentException> { controls.previewMerge("", 5) }
        assertTrue("Which repo" in e.message!!)
    }

    @Test
    fun failingChecksAreNotReady() {
        github.checksResult = ChecksSummary(Checks.Failing, listOf("lint"), 1)
        assertEquals(false, controls.previewMerge("app", 42).ready)
    }

    @Test
    fun draftsAndUnknownChecksAreNotReady() {
        github.pull = github.pull.copy(draft = true)
        assertEquals(false, controls.previewMerge("app", 42).ready)
        github.pull = github.pull.copy(draft = false)
        github.checksResult = ChecksSummary(Checks.Unknown, total = 1)
        assertEquals(false, controls.previewMerge("app", 42).ready)
    }

    @Test
    fun theSamePullNumberInTwoReposAsksWhichOne() {
        task("t1", app, pr = 5)
        task("t2", site, pr = 5)
        saved = listOf(app)
        val e = assertFailsWith<IllegalArgumentException> { controls.previewMerge("", 5) }
        assertTrue("more than one repo" in e.message!!.lowercase(), e.message)
        assertTrue("me/app" in e.message!! && "me/site" in e.message!!, e.message)
    }

    @Test
    fun stopWithNothingRunning() {
        task("t1", app)
        assertEquals("Nothing is running right now.", controls.stop(""))
    }

    @Test
    fun stopsTheOnlyActiveTask() {
        task("t1", app, status = TaskStatus.Working)
        assertTrue(controls.stop("").startsWith("Stopped Task t1"))
        assertEquals(TaskStatus.Stopped, store.get("t1")!!.status)
    }

    @Test
    fun asksWhichTaskWhenSeveralRun() {
        task("t1", app, status = TaskStatus.Working)
        task("t2", site, status = TaskStatus.Working)
        assertFailsWith<IllegalArgumentException> { controls.stop("") }
    }

    private val actions = object : AssistantActions {
        override fun context() = ""
        override fun startTask(repo: String, request: String) = ""
        override fun replyToTask(taskId: String, text: String) = ""
        override fun addRepo(name: String) = ""
        override fun previewMerge(repo: String, prNumber: Int?) = controls.previewMerge(repo, prNumber)
        override fun merge(repo: RepoRef, number: Int) = controls.merge(repo, number)
        override fun previewClose(repo: String, prNumber: Int?, reopen: Boolean) = controls.previewClose(repo, prNumber, reopen)
        override fun close(repo: RepoRef, number: Int) = controls.close(repo, number)
        override fun reopen(repo: RepoRef, number: Int) = controls.reopen(repo, number)
    }

    @Test
    fun mergeNeedsAYesInALaterTurn() {
        github.pull = github.pull.copy(state = "open", merged = false, draft = false)
        github.checksResult = ChecksSummary(Checks.Passing, total = 2)
        val gate = MergeConfirmation(actions)
        // Claude jumping straight to confirmed only gets the question.
        assertTrue("Ask the driver" in gate.handle("app", 42, confirmed = true, turn = 1))
        assertTrue(merged.isEmpty())
        // Confirming in the same turn still doesn't merge.
        gate.handle("app", 42, confirmed = true, turn = 1)
        assertTrue(merged.isEmpty())
        assertEquals("Merged pull request 42.", gate.handle("app", 42, confirmed = true, turn = 2))
        assertEquals(listOf("me/app" to 42), merged)
    }

    @Test
    fun confirmingADifferentPullRequestAsksAgain() {
        val gate = MergeConfirmation(actions)
        gate.handle("app", 42, confirmed = false, turn = 1)
        assertTrue("Ask the driver" in gate.handle("app", 43, confirmed = true, turn = 2))
        assertTrue(merged.isEmpty())
    }

    @Test
    fun notReadyIsNeverMerged() {
        github.checksResult = ChecksSummary(Checks.Failing, listOf("lint"), 1)
        val gate = MergeConfirmation(actions)
        assertTrue("can't merge" in gate.handle("app", 42, confirmed = false, turn = 1))
        assertTrue("can't merge" in gate.handle("app", 42, confirmed = true, turn = 2))
        assertTrue(merged.isEmpty())
    }

    @Test
    fun closeNeedsAYesInALaterTurn() {
        github.pull = github.pull.copy(state = "open", merged = false)
        github.stateChanges.clear()
        val gate = CloseConfirmation(actions)
        assertTrue("closing" in gate.handle("app", 42, confirmed = true, reopen = false, turn = 1))
        assertTrue(github.stateChanges.isEmpty())
        gate.handle("app", 42, confirmed = true, reopen = false, turn = 1)
        assertTrue(github.stateChanges.isEmpty())
        assertEquals("Closed pull request 42.", gate.handle("app", 42, confirmed = true, reopen = false, turn = 2))
        assertEquals(listOf(42 to false), github.stateChanges)
    }

    @Test
    fun reopenNeedsAYesInALaterTurn() {
        github.pull = github.pull.copy(state = "closed", merged = false)
        github.stateChanges.clear()
        val gate = CloseConfirmation(actions)
        assertTrue("reopening" in gate.handle("app", 42, confirmed = false, reopen = true, turn = 1))
        assertEquals("Reopened pull request 42.", gate.handle("app", 42, confirmed = true, reopen = true, turn = 2))
        assertEquals(listOf(42 to true), github.stateChanges)
    }

    @Test
    fun mergedPullRequestCannotCloseOrReopen() {
        github.pull = github.pull.copy(state = "closed", merged = true)
        github.stateChanges.clear()
        val gate = CloseConfirmation(actions)
        assertTrue("can't close" in gate.handle("app", 42, confirmed = false, reopen = false, turn = 1))
        assertTrue("can't reopen" in gate.handle("app", 42, confirmed = false, reopen = true, turn = 1))
        assertTrue(github.stateChanges.isEmpty())
    }
}
