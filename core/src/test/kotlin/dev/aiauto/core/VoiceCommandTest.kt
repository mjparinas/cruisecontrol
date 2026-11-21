package dev.aiauto.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spoken shortcuts handled by the app: status, stop, merge with confirmation, fixing failed checks, repeat, quiet, undo and start over. */
class VoiceCommandTest {
    @Test fun parsesCommands() {
        assertEquals(VoiceCommand.Merge, VoiceCommand.parse("Merge it."))
        assertEquals(VoiceCommand.Merge, VoiceCommand.parse("okay merge the PR please"))
        assertEquals(VoiceCommand.Status, VoiceCommand.parse("What's the status?"))
        assertEquals(VoiceCommand.Stop, VoiceCommand.parse("stop"))
        assertEquals(VoiceCommand.FixBuild, VoiceCommand.parse("fix it"))
        assertEquals(VoiceCommand.Yes, VoiceCommand.parse("Yes, do it"))
        assertEquals(VoiceCommand.No, VoiceCommand.parse("no"))
        assertEquals(VoiceCommand.Repeat, VoiceCommand.parse("Repeat that."))
        assertEquals(VoiceCommand.Repeat, VoiceCommand.parse("say that again please"))
        assertEquals(VoiceCommand.Quiet, VoiceCommand.parse("Quiet."))
        assertEquals(VoiceCommand.Quiet, VoiceCommand.parse("quiet mode please"))
        assertNull(VoiceCommand.parse("repeat the last step with the other file"))
        assertNull(VoiceCommand.parse("add a quiet mode switch"))
        assertNull(VoiceCommand.parse("merge the two settings screens into one"))
        assertNull(VoiceCommand.parse("add a status bar to the home screen"))
        assertEquals(VoiceCommand.Undo, VoiceCommand.parse("Undo that."))
        assertEquals(VoiceCommand.Undo, VoiceCommand.parse("take that back"))
        assertEquals(VoiceCommand.StartOver, VoiceCommand.parse("Start over, please"))
        assertEquals(VoiceCommand.StartOver, VoiceCommand.parse("start from scratch"))
        assertNull(VoiceCommand.parse("add an undo button to the editor"))
        assertNull(VoiceCommand.parse("start over the onboarding flow when the user logs out"))
    }

    private val repo = RepoRef("me", "app")

    private class MapStore : TaskStore {
        val map = LinkedHashMap<String, Task>()
        override fun get(id: String) = map[id]
        override fun all() = map.values.toList()
        override fun put(task: Task) { map[task.id] = task }
        override fun remove(id: String) { map.remove(id) }
    }

    private class Config : EngineConfig {
        override fun anthropicKey() = "key"
        override fun githubToken() = "token"
        override fun agentSetup() = AgentSetup("agent", 1, "env", AgentProfile())
        override fun saveAgentSetup(setup: AgentSetup) {}
        var quiet = false
        override fun quietMode() = quiet
        override fun setQuietMode(on: Boolean) { quiet = on }
    }

    private class FakeClaude : AgentClient {
        val sent = mutableListOf<String>()
        val interrupted = mutableListOf<String>()
        override fun createSetup(profile: AgentProfile, existing: AgentSetup?) = AgentSetup("agent", 1, "env", AgentProfile())
        override fun createSession(setup: AgentSetup, task: Task, githubToken: String) = "session-${task.id}"
        override fun sendPrompt(sessionId: String, prompt: String) { sent += prompt }
        override fun follow(
            sessionId: String,
            seenEventIds: MutableSet<String>,
            onConnected: () -> Unit,
            shouldStop: () -> Boolean,
            onUpdate: (AgentUpdate) -> Unit,
        ): Boolean {
            onUpdate(AgentUpdate.Said("Fixed."))
            onUpdate(AgentUpdate.TurnDone)
            return true
        }
        override fun interrupt(sessionId: String) { interrupted += sessionId }
        override fun archive(sessionId: String) {}
        override fun setBudget(sessionId: String, maxUsd: Double) {}
        override fun costUsd(sessionId: String): Double? = null
        override fun close() {}
    }

    private class FakeGitHub : GitHub("token") {
        var pr = PullRequest(7, "https://github.com/me/app/pull/7", nodeId = "PR_7", head = PullRequest.Head("abc"))
        var checksResult = ChecksSummary(Checks.Passing, total = 1)
        val merges = mutableListOf<Int>()
        override fun branchExists(repo: RepoRef, branch: String) = true
        override fun findPull(repo: RepoRef, branch: String): PullRequest? = pr
        override fun pull(repo: RepoRef, number: Int) = pr
        override fun checks(repo: RepoRef, sha: String) = checksResult
        override fun enableAutoMerge(pull: PullRequest) = false
        override fun merge(repo: RepoRef, number: Int): MergeResult {
            merges += number
            pr = pr.copy(merged = true, state = "closed")
            return MergeResult.Merged
        }
    }

    private val config = Config()
    private val store = MapStore()
    private val claude = FakeClaude()
    private val github = FakeGitHub()
    private val said = mutableListOf<String>()

    private val engine = TaskEngine(
        store = store,
        config = config,
        scope = CoroutineScope(SupervisorJob()),
        clock = { 1_000_000L },
        claudeFactory = { claude },
        githubFactory = { github },
        onMessage = { _, m -> said += m.text },
        sleep = {},
        dispatcher = Dispatchers.Unconfined,
    )

    private fun taskWithPr(checks: ChecksSummary? = null) = Task(
        "t1", repo, "Add a toggle", "claude/x", sessionId = "s1", status = TaskStatus.Done,
        prNumber = 7, prState = PrState.Open, checks = checks, createdMillis = 0, updatedMillis = 0,
    ).also(store::put)

    @Test fun mergesOnlyAfterTheDriverSaysYes() {
        taskWithPr()
        engine.prompt("t1", "merge it")
        assertEquals("Checks passed. Say yes to merge pull request 7 into main.", said.last())
        assertTrue(github.merges.isEmpty())
        assertTrue(claude.sent.isEmpty())

        engine.prompt("t1", "yes")
        assertEquals(listOf(7), github.merges)
        assertTrue(said.any { it.startsWith("Merged pull request 7") })
        assertNull(store.get("t1")!!.pending)
    }

    @Test fun noCancelsTheMerge() {
        taskWithPr()
        engine.prompt("t1", "merge")
        engine.prompt("t1", "no")
        assertEquals("Okay, leaving it as is.", said.last())
        engine.prompt("t1", "yes")
        assertTrue(github.merges.isEmpty())
        assertEquals("yes", claude.sent.last(), "A yes with nothing pending goes to Claude")
    }

    @Test fun refusesToMergeFailingChecksAndOffersAFix() {
        taskWithPr()
        github.checksResult = ChecksSummary(Checks.Failing, failing = listOf("build"), total = 1)
        engine.prompt("t1", "merge it")
        assertTrue(said.last().contains("so I won't merge it. Say yes to have Claude fix them."))

        engine.prompt("t1", "yes")
        assertTrue(github.merges.isEmpty())
        assertTrue(claude.sent.last().startsWith("The checks failed on pull request 7 after your last push: build."))
        assertEquals("yes", store.get("t1")!!.messages.last { it.speaker == Speaker.You }.text)
    }

    @Test fun fixItSendsTheFailingChecksToClaude() {
        taskWithPr(ChecksSummary(Checks.Failing, failing = listOf("lint", "test"), total = 2))
        engine.prompt("t1", "fix it")
        assertTrue(claude.sent.last().contains("lint, test"))
    }

    @Test fun fixItWithoutFailingChecksGoesToClaudeAsIs() {
        taskWithPr(ChecksSummary(Checks.Passing, total = 1))
        engine.prompt("t1", "fix it")
        assertEquals("fix it", claude.sent.last())
    }

    @Test fun answersStatusWithoutClaude() {
        taskWithPr(ChecksSummary(Checks.Pending, total = 1))
        engine.prompt("t1", "status")
        assertEquals("Checks are still running on pull request 7.", said.last())
        assertTrue(claude.sent.isEmpty())
    }

    @Test fun undoAsksClaudeToRevertItsLastChange() {
        taskWithPr()
        engine.prompt("t1", "undo that")
        assertEquals(TaskEngine.UNDO_PROMPT, claude.sent.last())
        assertEquals("undo that", store.get("t1")!!.messages.first { it.speaker == Speaker.You }.text)
    }

    @Test fun undoWithNothingDoneYetSaysSo() {
        store.put(Task("t2", repo, "Add a toggle", "claude/x", status = TaskStatus.Stopped, createdMillis = 0, updatedMillis = 0))
        engine.prompt("t2", "undo")
        assertEquals("There's nothing to undo yet.", said.last())
        assertTrue(claude.sent.isEmpty())
    }

    @Test fun startOverOnlyAfterTheDriverSaysYes() {
        taskWithPr()
        store.put(store.get("t1")!!.copy(messages = listOf(TaskMessage("m1", Speaker.You, "add a dark mode toggle", 0))))
        engine.prompt("t1", "start over")
        assertEquals("Say yes to throw away the work on claude/x and start the request over from main.", said.last())
        assertTrue(claude.sent.isEmpty())

        engine.prompt("t1", "yes")
        val sent = claude.sent.single()
        assertTrue(sent.startsWith("Start over.") && sent.endsWith("add a dark mode toggle"), sent)
    }

    @Test fun noKeepsTheWorkWhenAskedToStartOver() {
        taskWithPr()
        engine.prompt("t1", "start over")
        engine.prompt("t1", "no")
        assertEquals("Okay, leaving it as is.", said.last())
        assertTrue(claude.sent.isEmpty())
    }

    @Test fun undoAndStartOverDoNothingOnceMerged() {
        store.put(taskWithPr().copy(prState = PrState.Merged))
        engine.prompt("t1", "undo that")
        engine.prompt("t1", "start over")
        assertEquals("Pull request 7 is already merged. Start a new task to change it.", said.last())
        assertTrue(claude.sent.isEmpty())
    }

    @Test fun repeatReadsTheLastMessageAgainAndKeepsTheQuestionOpen() {
        taskWithPr()
        engine.prompt("t1", "merge it")
        val question = said.last()
        engine.prompt("t1", "repeat that")
        assertEquals(question, said.last())
        assertTrue(claude.sent.isEmpty())

        engine.prompt("t1", "yes")
        assertEquals(listOf(7), github.merges)
    }

    @Test fun repeatWithNothingSaidYetSaysSo() {
        taskWithPr()
        engine.prompt("t1", "repeat")
        assertEquals("There's nothing to repeat yet.", said.last())
    }

    @Test fun quietTurnsOnQuietMode() {
        taskWithPr()
        engine.prompt("t1", "quiet")
        assertTrue(config.quiet)
        assertTrue(said.last().startsWith("Quiet mode is on."))
        engine.prompt("t1", "be quiet")
        assertEquals("Quiet mode is already on.", said.last())
        assertTrue(claude.sent.isEmpty())
    }

    @Test fun stopWhenIdleSaysSo() {
        taskWithPr()
        engine.prompt("t1", "stop")
        assertEquals("Claude isn't working on this right now.", said.last())
        assertTrue(claude.interrupted.isEmpty())
    }
}
