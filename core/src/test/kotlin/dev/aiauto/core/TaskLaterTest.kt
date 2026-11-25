package dev.aiauto.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tasks saved for later, and pictures shared into a task. */
class TaskLaterTest {
    private val repo = RepoRef("me", "app")
    private val zone = ZoneId.of("America/Toronto")
    private var now = ZonedDateTime.of(2026, 11, 25, 17, 0, 0, 0, zone).toInstant().toEpochMilli()

    private class MapStore : TaskStore {
        val map = LinkedHashMap<String, Task>()
        override fun get(id: String) = map[id]
        override fun all() = map.values.toList()
        override fun put(task: Task) { map[task.id] = task }
        override fun remove(id: String) { map.remove(id) }
    }

    private class Config : EngineConfig {
        val saved = mutableMapOf<String, List<TaskImage>>()
        override fun anthropicKey() = "key"
        override fun githubToken() = "token"
        override fun agentSetup() = AgentSetup("agent", 1, "env", AgentProfile())
        override fun saveAgentSetup(setup: AgentSetup) {}
        override fun deviceId() = "phone"
        override fun saveImages(taskId: String, images: List<TaskImage>) { saved[taskId] = images }
        override fun images(taskId: String) = saved[taskId].orEmpty()
        override fun dropImages(taskId: String) { saved.remove(taskId) }
    }

    private class FakeClaude : AgentClient {
        val sent = mutableListOf<Pair<String, List<TaskImage>>>()
        override fun createSetup(profile: AgentProfile, existing: AgentSetup?) = AgentSetup("agent", 1, "env", profile)
        override fun createSession(setup: AgentSetup, task: Task, githubToken: String) = "session"
        override fun sendPrompt(sessionId: String, prompt: String) { sent += prompt to emptyList() }
        override fun sendPrompt(sessionId: String, prompt: String, images: List<TaskImage>) { sent += prompt to images }
        override fun follow(
            sessionId: String,
            seenEventIds: MutableSet<String>,
            onConnected: () -> Unit,
            shouldStop: () -> Boolean,
            canReview: () -> Boolean,
            onUpdate: (AgentUpdate) -> Unit,
        ): Boolean {
            onConnected()
            onUpdate(AgentUpdate.Said("All done."))
            onUpdate(AgentUpdate.TurnDone)
            return true
        }
        override fun close() {}
    }

    private val store = MapStore()
    private val config = Config()
    private val claude = FakeClaude()
    private fun engine() = TaskEngine(
        store = store,
        config = config,
        scope = CoroutineScope(SupervisorJob()),
        clock = { now },
        claudeFactory = { claude },
        githubFactory = { throw GitHubException(404, "no branch") },
        sleep = {},
        dispatcher = Dispatchers.Unconfined,
        zone = { zone },
    )

    @Test fun aTaskForLaterWaitsUntilItsTime() {
        val engine = engine()
        val at = now + 3 * 60 * 60_000L
        val task = engine.start(repo, "update the dependencies", startAtMillis = at)
        val saved = store.get(task.id)!!
        assertTrue(saved.isScheduled)
        assertFalse(saved.isActive, "A task for later doesn't count as running")
        assertTrue(claude.sent.isEmpty())
        assertTrue(saved.messages.any { it.text == "Saved for later. Claude starts at 8:00 PM today." })
        assertEquals(at, engine.nextStartMillis())

        engine.startDue()
        assertTrue(claude.sent.isEmpty(), "Not yet")

        now = at
        engine.startDue()
        assertEquals(1, claude.sent.size)
        val started = store.get(task.id)!!
        assertNull(started.startAtMillis)
        assertEquals(TaskStatus.Done, started.status)
        assertNull(engine.nextStartMillis())
    }

    @Test fun aTimeInThePastStartsRightAway() {
        engine().start(repo, "fix it", startAtMillis = now - 1)
        assertEquals(1, claude.sent.size)
    }

    @Test fun startNowSkipsTheWait() {
        val engine = engine()
        val task = engine.start(repo, "update the dependencies", startAtMillis = now + 60 * 60_000L)
        engine.startNow(task.id)
        assertEquals(1, claude.sent.size)
        assertFalse(store.get(task.id)!!.isScheduled)
    }

    @Test fun stoppingATaskForLaterCancelsIt() {
        val engine = engine()
        val task = engine.start(repo, "update the dependencies", startAtMillis = now + 60 * 60_000L)
        engine.stop(task.id)
        val stopped = store.get(task.id)!!
        assertEquals(TaskStatus.Stopped, stopped.status)
        assertFalse(stopped.isScheduled)
        now += 2 * 60 * 60_000L
        engine.startDue()
        assertTrue(claude.sent.isEmpty())
    }

    @Test fun resumeAllStartsTasksThatCameDueWhileTheAppWasClosed() {
        val engine = engine()
        engine.start(repo, "update the dependencies", startAtMillis = now + 60_000L)
        now += 120_000L
        engine.resumeAll()
        assertEquals(1, claude.sent.size)
    }

    @Test fun sharedPicturesGoWithTheFirstPromptOnly() {
        val image = TaskImage("image/jpeg", "abc")
        val task = engine().start(repo, "the button is cut off here", images = listOf(image))
        assertEquals(1, store.get(task.id)!!.imageCount)
        assertEquals(listOf(image), claude.sent.single().second)
        assertTrue(config.saved.isEmpty(), "Dropped once sent")
        assertTrue(store.get(task.id)!!.messages.any { it.text == "With 1 screenshot." })
    }

    @Test fun picturesWaitWithATaskForLater() {
        val engine = engine()
        val image = TaskImage("image/png", "xyz")
        val task = engine.start(repo, "match this design", images = listOf(image), startAtMillis = now + 60_000L)
        assertEquals(listOf(image), config.saved[task.id])
        now += 60_000L
        engine.startDue()
        assertEquals(listOf(image), claude.sent.single().second)
    }

    @Test fun noMoreThanFourPictures() {
        val images = (1..6).map { TaskImage("image/png", "i$it") }
        engine().start(repo, "look at these", images = images)
        assertEquals(TaskImages.MAX, claude.sent.single().second.size)
    }

    @Test fun clearingATaskDropsItsPictures() {
        val engine = engine()
        val task = engine.start(repo, "later", images = listOf(TaskImage("image/png", "x")), startAtMillis = now + 60_000L)
        engine.clear(task.id)
        assertTrue(config.saved.isEmpty())
    }

    @Test fun startLaterOffersAnHourTonightAndTomorrowMorning() {
        val options = StartLater.options(now, zone)
        assertEquals(listOf("In an hour", "Tonight at 8 PM", "Tomorrow at 8 AM"), options.map { it.label })
        assertEquals("at 6:00 PM today", StartLater.spoken(options[0].atMillis, now, zone))
        assertEquals("at 8:00 PM today", StartLater.spoken(options[1].atMillis, now, zone))
        assertEquals("at 8:00 AM tomorrow", StartLater.spoken(options[2].atMillis, now, zone))
    }

    @Test fun lateInTheEveningTonightBecomesTomorrowNight() {
        val late = ZonedDateTime.of(2026, 11, 25, 21, 0, 0, 0, zone).toInstant().toEpochMilli()
        val options = StartLater.options(late, zone)
        assertEquals("Tomorrow at 8 PM", options[1].label)
        assertEquals("at 8:00 PM tomorrow", StartLater.spoken(options[1].atMillis, late, zone))
    }

    @Test fun furtherAwayDaysAreNamed() {
        val friday = ZonedDateTime.of(2026, 11, 27, 21, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("at 9:30 PM on Friday, November 27", StartLater.spoken(friday, now, zone))
    }

    @Test fun spokenDelaysAreKeptSensible() {
        assertEquals(now + 60_000L, StartLater.inMinutes(now, 0))
        assertEquals(now + StartLater.MAX_WAIT_MINUTES * 60_000L, StartLater.inMinutes(now, 100_000))
    }

    @Test fun bigPicturesAreScaledDownKeepingTheirShape() {
        assertEquals(1568 to 882, TaskImages.scaledSize(4000, 2250))
        assertEquals(800 to 600, TaskImages.scaledSize(800, 600))
        assertEquals(2, TaskImages.sampleSize(4000, 2250))
        assertEquals(1, TaskImages.sampleSize(1080, 2400))
        assertEquals("3 screenshots", TaskImages.describe(3))
    }
}
