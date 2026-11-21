package dev.aiauto.core

import com.anthropic.core.JsonValue
import com.anthropic.core.http.Headers
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.UnauthorizedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskEngineTest {
    private val repo = RepoRef("me", "app")

    private class MapStore : TaskStore {
        val map = LinkedHashMap<String, Task>()
        override fun get(id: String) = map[id]
        override fun all() = map.values.toList()
        override fun put(task: Task) { map[task.id] = task }
        override fun remove(id: String) { map.remove(id) }
    }

    private class Config(var setup: AgentSetup? = AgentSetup("agent", 1, "env", AgentProfile())) : EngineConfig {
        override fun anthropicKey() = "key"
        override fun githubToken() = "token"
        override fun agentSetup() = setup
        override fun saveAgentSetup(setup: AgentSetup) { this.setup = setup }
        var quiet = false
        override fun quietMode() = quiet
        var profile = AgentProfile()
        override fun agentProfile() = profile
        override fun deviceId() = "phone"
    }

    /** Scripted Claude: each list entry is what one call does, then it behaves normally. */
    private class FakeClaude : AgentClient {
        val sessionFailures = ArrayDeque<Exception>()
        val sendFailures = ArrayDeque<Exception>()
        /** Each follow() call takes the next step: an exception to throw, or updates to emit. */
        val followSteps = ArrayDeque<Any>()
        val sent = mutableListOf<String>()
        var sessionsCreated = 0
        var setupsCreated = 0

        var lastExisting: AgentSetup? = null
        var lastSessionSetup: AgentSetup? = null
        override fun createSetup(profile: AgentProfile, existing: AgentSetup?): AgentSetup {
            setupsCreated++
            lastExisting = existing
            val webEnvironmentId = existing?.webEnvironmentId ?: if (profile.webSearch) "web-env" else null
            return AgentSetup("agent2", 1, existing?.environmentId ?: "env2", profile, webEnvironmentId)
        }
        override fun createSession(setup: AgentSetup, task: Task, githubToken: String): String {
            sessionFailures.removeFirstOrNull()?.let { throw it }
            sessionsCreated++
            lastSessionSetup = setup
            return "session-${setup.agentId}"
        }
        override fun sendPrompt(sessionId: String, prompt: String) {
            sendFailures.removeFirstOrNull()?.let { throw it }
            sent += prompt
        }
        override fun follow(
            sessionId: String,
            seenEventIds: MutableSet<String>,
            onConnected: () -> Unit,
            shouldStop: () -> Boolean,
            onUpdate: (AgentUpdate) -> Unit,
        ): Boolean {
            when (val step = followSteps.removeFirstOrNull()) {
                is Throwable -> throw step
                is List<*> -> { onConnected(); step.forEach { onUpdate(it as AgentUpdate) } }
                else -> { onConnected(); onUpdate(AgentUpdate.Said("All done.")); onUpdate(AgentUpdate.TurnDone) }
            }
            return true
        }
        override fun close() {}
    }

    private val store = MapStore()
    private val config = Config()
    private val claude = FakeClaude()
    private var spoken = mutableListOf<String>()
    private fun engine(onMessage: (Task, TaskMessage) -> Unit = { _, m -> spoken += m.text }) = TaskEngine(
        store = store,
        config = config,
        scope = CoroutineScope(SupervisorJob()),
        claudeFactory = { claude },
        githubFactory = { throw GitHubException(404, "no branch") },
        onMessage = onMessage,
        sleep = {},
        dispatcher = Dispatchers.Unconfined,
    )

    private fun dropped() = AnthropicIoException("connection reset")

    @Test fun reconnectsAfterTheStreamDrops() {
        claude.followSteps.addAll(listOf(dropped(), dropped()))
        val task = engine().start(repo, "add a test")
        val done = store.get(task.id)!!
        assertEquals(TaskStatus.Done, done.status)
        assertEquals(1, claude.sessionsCreated)
        assertEquals(1, claude.sent.size)
        assertTrue(done.messages.any { it.text == "All done." })
    }

    @Test fun quietModeAnnouncesOnlyTheResult() {
        config.quiet = true
        claude.followSteps.add(
            listOf(
                AgentUpdate.Said("Looking at the settings screen."),
                AgentUpdate.Progress("Editing files"),
                AgentUpdate.Said("Added the toggle and tests pass."),
                AgentUpdate.TurnDone,
            ),
        )
        val task = engine().start(repo, "add a toggle")
        val done = store.get(task.id)!!
        assertEquals(TaskStatus.Done, done.status)
        assertTrue(done.messages.any { it.text == "Looking at the settings screen." })
        assertFalse(spoken.any { it == "Looking at the settings screen." || it.startsWith("Claude is starting") })
        assertEquals("Added the toggle and tests pass.", spoken.first())
    }

    @Test fun makesANewAgentWhenSettingsChangeAndKeepsTheEnvironment() {
        engine().start(repo, "first")
        assertEquals(0, claude.setupsCreated)
        config.profile = AgentProfile(model = ModelChoice.Sonnet.id, rules = listOf(StandingRule.AddTests.name))
        engine().start(repo, "second")
        assertEquals(1, claude.setupsCreated)
        assertEquals(config.profile, config.setup?.profile)
        assertEquals("env", claude.lastExisting?.environmentId)
        engine().start(repo, "third")
        assertEquals(1, claude.setupsCreated)
    }

    @Test fun webSearchSwitchReachesNewTasks() {
        engine().start(repo, "first")
        assertEquals("env", claude.lastSessionSetup?.sessionEnvironmentId)

        config.profile = AgentProfile(webSearch = true)
        engine().start(repo, "look up the docs")
        assertEquals(1, claude.setupsCreated)
        assertEquals(true, claude.lastSessionSetup?.profile?.webSearch)
        assertEquals("web-env", claude.lastSessionSetup?.sessionEnvironmentId)

        config.profile = AgentProfile(webSearch = false)
        engine().start(repo, "offline again")
        assertEquals(2, claude.setupsCreated)
        assertEquals(false, claude.lastSessionSetup?.profile?.webSearch)
        assertEquals("env", claude.lastSessionSetup?.sessionEnvironmentId)
        assertEquals("web-env", config.setup?.webEnvironmentId)
    }

    @Test fun leavesTasksFromOtherDevicesAloneUntilYouReply() {
        val now = System.currentTimeMillis()
        store.put(Task("t1", repo, "other", "b", sessionId = "s", status = TaskStatus.Working, createdMillis = now, updatedMillis = now, device = "tablet"))
        val engine = engine()
        engine.resumeAll()
        assertEquals(0, claude.sent.size)
        assertEquals("tablet", store.get("t1")!!.device)
        engine.prompt("t1", "carry on")
        assertEquals("phone", store.get("t1")!!.device)
        assertEquals(1, claude.sent.size)
    }

    @Test fun recreatesAMissingAgentSetupOnce() {
        claude.sessionFailures += NotFoundException.builder().headers(Headers.builder().build()).body(JsonValue.from(mapOf<String, Any>())).build()
        val task = engine().start(repo, "fix the build")
        assertEquals(TaskStatus.Done, store.get(task.id)!!.status)
        assertEquals(1, claude.setupsCreated)
        assertEquals("agent2", config.setup?.agentId)
    }

    @Test fun keepsAnUnsentPromptAndResendsItWhenTheNetworkReturns() {
        repeat(3) { claude.sendFailures += dropped() }
        val engine = engine()
        val task = engine.start(repo, "rename the screen")
        val stopped = store.get(task.id)!!
        assertEquals(TaskStatus.Failed, stopped.status)
        assertTrue(stopped.interrupted)
        assertTrue(stopped.pendingPrompt!!.contains("rename the screen"))
        assertTrue(spoken.last().contains("reconnect"))

        engine.onNetworkAvailable()
        val resumed = store.get(task.id)!!
        assertEquals(TaskStatus.Done, resumed.status)
        assertFalse(resumed.interrupted)
        assertNull(resumed.pendingPrompt)
        assertEquals(1, claude.sessionsCreated)
        assertEquals(1, claude.sent.size)
    }

    @Test fun givesUpOnAPersistentlyDeadStreamButStaysResumable() {
        repeat(20) { claude.followSteps += dropped() }
        val task = engine().start(repo, "update docs")
        val stopped = store.get(task.id)!!
        assertEquals(TaskStatus.Failed, stopped.status)
        assertTrue(stopped.interrupted)
        assertNull(stopped.pendingPrompt)
    }

    @Test fun keepsRetryingACutOffTaskOnItsOwnAndAnnouncesTheDropOnce() {
        var now = 1_000_000L
        val engine = TaskEngine(
            store = store, config = config, scope = CoroutineScope(SupervisorJob()), clock = { now },
            claudeFactory = { claude }, githubFactory = { throw GitHubException(404, "no branch") },
            onMessage = { _, m -> spoken += m.text }, sleep = {}, dispatcher = Dispatchers.Unconfined,
        )
        repeat(6) { claude.sendFailures += dropped() }
        val task = engine.start(repo, "rename the screen")
        assertTrue(store.get(task.id)!!.interrupted)
        assertTrue(engine.isReconnecting())

        // Too soon: nothing happens.
        engine.resumeInterrupted()
        assertEquals(3, claude.sendFailures.size)

        now += 31_000
        engine.resumeInterrupted()
        assertTrue(store.get(task.id)!!.interrupted)
        assertEquals(0, claude.sendFailures.size)
        assertEquals(1, spoken.count { "reconnect" in it })

        now += 31_000
        engine.resumeInterrupted()
        val done = store.get(task.id)!!
        assertEquals(TaskStatus.Done, done.status)
        assertNull(done.interruptedSinceMillis)
        assertFalse(engine.isReconnecting())
    }

    @Test fun stopsRetryingOnItsOwnAfterAWhile() {
        var now = 1_000_000L
        val engine = TaskEngine(
            store = store, config = config, scope = CoroutineScope(SupervisorJob()), clock = { now },
            claudeFactory = { claude }, githubFactory = { throw GitHubException(404, "no branch") },
            sleep = {}, dispatcher = Dispatchers.Unconfined,
        )
        repeat(3) { claude.sendFailures += dropped() }
        val task = engine.start(repo, "go")
        now += 3 * 60 * 60 * 1000L
        assertFalse(engine.isReconnecting())
        engine.resumeInterrupted()
        assertTrue(store.get(task.id)!!.interrupted)
        assertEquals(0, claude.sent.size)
    }

    @Test fun anUnexpectedErrorFailsTheTaskInsteadOfLeavingItStuck() {
        claude.followSteps += StackOverflowError()
        val task = engine().start(repo, "go")
        assertEquals(TaskStatus.Failed, store.get(task.id)!!.status)
    }

    @Test fun badKeyFailsWithAClearMessage() {
        claude.sessionFailures += UnauthorizedException.builder().headers(Headers.builder().build()).body(JsonValue.from(mapOf<String, Any>())).build()
        val task = engine().start(repo, "anything")
        val failed = store.get(task.id)!!
        assertEquals(TaskStatus.Failed, failed.status)
        assertFalse(failed.interrupted)
        assertEquals("Claude rejected your API key. Check it in Settings.", failed.lastMessage?.text)
    }

    @Test fun followUpQueuedWhileOfflineIsSentAfterRetry() {
        val engine = engine()
        val task = engine.start(repo, "first")
        claude.sendFailures.addAll(listOf(dropped(), dropped(), dropped()))
        engine.prompt(task.id, "second")
        assertEquals("second", store.get(task.id)!!.pendingPrompt)
        engine.retry(task.id)
        assertEquals(listOf("second"), claude.sent.drop(1))
        assertEquals(TaskStatus.Done, store.get(task.id)!!.status)
    }

    @Test fun aNotificationCrashDoesNotStopTheTask() {
        val task = engine(onMessage = { _, _ -> error("notification channel missing") }).start(repo, "go")
        assertEquals(TaskStatus.Done, store.get(task.id)!!.status)
    }

    @Test fun resumesTasksThatWereMidTurnWhenTheAppClosed() {
        val now = 0L
        store.put(Task("t1", repo, "x", "b", sessionId = "s", status = TaskStatus.Working, createdMillis = now, updatedMillis = now))
        store.put(Task("t2", repo, "y", "b", sessionId = null, status = TaskStatus.Starting, createdMillis = now, updatedMillis = now,
            messages = listOf(TaskMessage("m", Speaker.You, "do y", now))))
        engine().resumeAll()
        assertEquals(TaskStatus.Done, store.get("t1")!!.status)
        assertEquals(TaskStatus.Done, store.get("t2")!!.status)
        assertEquals(1, claude.sessionsCreated)
    }

    @Test fun classifiesErrors() {
        assertTrue(Failures.isTransient(dropped()))
        assertTrue(Failures.isTransient(java.net.SocketTimeoutException()))
        assertTrue(Failures.isTransient(GitHubException(502, "bad gateway")))
        assertFalse(Failures.isTransient(GitHubException(401, "bad creds")))
        assertFalse(Failures.isTransient(IllegalStateException("Add a GitHub token in Settings.")))
        assertEquals("GitHub rejected your token. Update it in Settings.", Failures.describe(GitHubException(401, "x")))
        assertEquals("No internet connection.", Failures.describe(java.net.UnknownHostException("api.anthropic.com")))
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..6).map(Failures::backoffMillis))
    }
}
