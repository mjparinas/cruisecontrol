package dev.aiauto.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * Runs tasks: creates a session per task, sends prompts, follows the event stream,
 * and opens a pull request when Claude pushes its branch.
 *
 * Every step can be repeated safely, so a task that was cut off (lost signal, app killed, Claude busy)
 * picks up where it left off: a session is created only once, a prompt is sent only until it is
 * accepted, and the stream is followed again from the session's history.
 *
 * The engine owns the task lifecycle and the Claude session. It hands the rest to focused helpers:
 * [TaskJournal] records state and conversation lines, [SpendingLimit] keeps the daily budget, and
 * [PullRequests] opens, watches and merges pull requests.
 */
class TaskEngine(
    private val store: TaskStore,
    private val config: EngineConfig,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val claudeFactory: (String) -> AgentClient = ::ClaudeAgents,
    githubFactory: (String) -> GitHub = { GitHub(it) },
    /** Called for each new line, so the app can notify and read it aloud. */
    onMessage: (Task, TaskMessage) -> Unit = { _, _ -> },
    /** Called for each new progress note, so the app can read it aloud without posting a notification. */
    onProgress: (Task, String) -> Unit = { _, _ -> },
    /** Called for things worth interrupting the driver for: failed checks, a question from Claude, a stuck task. */
    private val onAlert: (Task, AlertKind, String) -> Unit = { _, _, _ -> },
    /** Waits between retries. Tests pass a no-op. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val dispatcher: CoroutineContext = Dispatchers.IO,
) {
    private val journal = TaskJournal(store, clock, onMessage, onProgress, config::quietMode)
    private val spending = SpendingLimit(store, config, journal)
    private val pullRequests = PullRequests(store, config, journal, clock, githubFactory, ::autoFix, ::alert)
    /** Tasks already reported as stuck in their current turn. */
    private val stuckAlerted = ConcurrentHashMap.newKeySet<String>()

    private val running = ConcurrentHashMap<String, Job>()
    private val seenEvents = ConcurrentHashMap<String, MutableSet<String>>()

    fun isRunning(taskId: String) = running[taskId]?.isActive == true

    /** Tasks this device runs. With sync, tasks another device started are only shown here, not followed. */
    private fun mine(): List<Task> {
        val me = config.deviceId()
        return store.all().filter { it.device == null || it.device == me }
    }

    // ---- Task lifecycle ------------------------------------------------------------------------

    /** Starts a new task on [repo] from a spoken or typed [request]. Returns the new task. */
    fun start(repo: RepoRef, request: String): Task {
        val now = clock()
        val id = UUID.randomUUID().toString().take(8)
        val task = Task(
            id = id,
            repo = repo,
            title = titleFrom(request),
            branch = "claude/${slug(request)}-$id",
            createdMillis = now,
            updatedMillis = now,
            device = config.deviceId(),
        )
        store.put(task)
        journal.append(id, Speaker.You, request)
        launchTurn(id)
        return store.get(id) ?: task
    }

    /**
     * Adds a follow-up prompt to an existing task. Short spoken commands ("status", "stop", "merge it",
     * "fix it", "repeat that", "quiet", "undo that", "start over") and yes or no answers to the app's own questions
     * are handled here; the rest goes to Claude.
     */
    fun prompt(taskId: String, text: String) {
        val task = store.get(taskId) ?: return
        val command = VoiceCommand.parse(text)
        val pending = task.pending
        // Hearing a question again shouldn't cancel it, so "repeat that" then "yes" still answers it.
        if (pending != null && command != VoiceCommand.Repeat) journal.update(taskId) { it.copy(pending = null) }
        val checksFailing = task.hasOpenPr && task.checks?.state == Checks.Failing
        val conflicting = task.hasOpenPr && task.conflicts
        when {
            command == VoiceCommand.Yes && pending == PendingAction.FixConflicts ||
                command == VoiceCommand.FixBuild && conflicting -> send(taskId, text, fixConflictsPrompt(task))
            command == VoiceCommand.Yes && pending == PendingAction.FixChecks ||
                command == VoiceCommand.FixBuild && checksFailing -> send(taskId, text, fixChecksPrompt(task))
            command == VoiceCommand.Yes && pending == PendingAction.StartOver -> send(taskId, text, startOverPrompt(task))
            (command == VoiceCommand.Undo || command == VoiceCommand.StartOver) && task.prState == PrState.Merged -> {
                journal.append(taskId, Speaker.You, text)
                journal.status(taskId, "Pull request ${task.prNumber} is already merged. Start a new task to change it.")
            }
            command == VoiceCommand.Undo && task.sessionId == null -> {
                journal.append(taskId, Speaker.You, text)
                journal.status(taskId, "There's nothing to undo yet.")
            }
            command == VoiceCommand.Undo -> send(taskId, text, UNDO_PROMPT)
            command == VoiceCommand.StartOver -> {
                journal.append(taskId, Speaker.You, text)
                ask(taskId, PendingAction.StartOver, "Say yes to throw away the work on ${task.branch} and start the request over from ${task.repo.defaultBranch}.")
            }
            command == VoiceCommand.Yes && pending == PendingAction.Merge -> {
                journal.append(taskId, Speaker.You, text)
                scope.launch(dispatcher) { runCatching { mergeNow(taskId) } }
            }
            command == VoiceCommand.No && pending != null -> {
                journal.append(taskId, Speaker.You, text)
                journal.status(taskId, "Okay, leaving it as is.")
            }
            command == VoiceCommand.Stop -> {
                journal.append(taskId, Speaker.You, text)
                if (isRunning(taskId) || task.isActive) stop(taskId) else journal.status(taskId, "Claude isn't working on this right now.")
            }
            command == VoiceCommand.Status -> {
                journal.append(taskId, Speaker.You, text)
                journal.status(taskId, statusLine(task))
            }
            command == VoiceCommand.Merge -> {
                journal.append(taskId, Speaker.You, text)
                scope.launch(dispatcher) {
                    runCatching { askToMerge(taskId) }.onFailure { journal.status(taskId, "Couldn't check the pull request. ${Failures.describe(it)}") }
                }
            }
            command == VoiceCommand.Repeat -> {
                if (!journal.repeatLast(taskId)) journal.status(taskId, "There's nothing to repeat yet.")
            }
            command == VoiceCommand.Quiet -> {
                journal.append(taskId, Speaker.You, text)
                if (config.quietMode()) {
                    journal.status(taskId, "Quiet mode is already on.")
                } else {
                    config.setQuietMode(true)
                    journal.status(taskId, "Quiet mode is on. I'll only read out results and alerts. Turn it off in Settings.")
                }
            }
            else -> send(taskId, text, text)
        }
    }

    /** Shows [shown] as the driver's line and sends [text] to Claude. With no [shown], the app is sending it on its own. */
    private fun send(taskId: String, shown: String?, text: String) {
        val task = store.get(taskId) ?: return
        if (shown != null) {
            journal.append(taskId, Speaker.You, shown)
            // The driver is back in the loop, so automatic fixes may start over.
            journal.update(taskId) { it.copy(autoFixes = 0) }
        }
        if (task.archived) journal.update(taskId) { it.copy(archived = false) }
        // Without a session the prompt joins the first message; otherwise it queues behind any unsent one.
        if (task.sessionId != null) {
            journal.update(taskId) { it.copy(pendingPrompt = listOfNotNull(it.pendingPrompt, text).joinToString("\n\n")) }
        }
        launchTurn(taskId)
    }

    /**
     * Sends a fix for failing checks or merge conflicts that the app found on its own. A refresh can run inside
     * the task's own turn, so wait for that turn to end first, and skip it if the driver started another one.
     */
    private fun autoFix(taskId: String, prompt: String) {
        val current = running[taskId]
        scope.launch(dispatcher) {
            current?.join()
            if (!isRunning(taskId)) send(taskId, null, prompt)
        }
    }

    /** Picks a stopped task back up: creates its session if it never got one, resends unsent prompts, and reattaches. */
    fun retry(taskId: String) {
        if (isRunning(taskId) || store.get(taskId) == null) return
        journal.progress(taskId, "Trying again")
        launchTurn(taskId)
    }

    /** Re-attaches to tasks that were mid-turn when the app was closed, and to tasks cut off by a lost connection. */
    fun resumeAll() {
        mine().filter { it.isActive }.forEach { retry(it.id) }
    }

    /** Call when the phone gets a working network again. Resumes tasks that stopped because it was lost. */
    fun onNetworkAvailable() {
        mine().filter { it.status == TaskStatus.Failed && it.interrupted }.forEach { retry(it.id) }
    }

    /**
     * Retries tasks cut off by a lost connection, without waiting for the network callback, which doesn't fire
     * when the phone stayed online but the connection was flaky or a server was down. Call it regularly.
     * Each task waits [RESUME_INTERVAL_MILLIS] between tries, and gives up after [AUTO_RESUME_MILLIS].
     */
    fun resumeInterrupted() {
        val now = clock()
        mine()
            .filter { waitingToReconnect(it, now) && now - it.updatedMillis >= RESUME_INTERVAL_MILLIS }
            .forEach { launchTurn(it.id) }
    }

    /** True while some task is cut off and still being retried, so the app should stay awake. */
    fun isReconnecting(): Boolean {
        val now = clock()
        return mine().any { waitingToReconnect(it, now) }
    }

    private fun waitingToReconnect(task: Task, now: Long): Boolean =
        task.status == TaskStatus.Failed && task.interrupted && !isRunning(task.id) &&
            now - (task.interruptedSinceMillis ?: task.updatedMillis) < AUTO_RESUME_MILLIS

    /**
     * Alerts once per turn for each task that's been working for [STUCK_MILLIS] without a single update,
     * so a hung session doesn't burn money unnoticed. Call it regularly.
     */
    fun checkStuck() {
        val now = clock()
        store.all()
            .filter { it.status == TaskStatus.Working && isRunning(it.id) && now - it.updatedMillis >= STUCK_MILLIS && it.id !in stuckAlerted }
            .forEach { task ->
                stuckAlerted += task.id
                val minutes = (now - task.updatedMillis) / 60_000
                val text = "Claude hasn't sent an update on ${task.title} in $minutes minutes. Say stop to stop it, or status to check on it."
                journal.status(task.id, text, notify = false)
                alert(task.id, AlertKind.Stuck, text)
            }
    }

    private fun alert(taskId: String, kind: AlertKind, text: String) {
        val task = store.get(taskId) ?: return
        // An alert that can't be shown must never stop the task.
        runCatching { onAlert(task, kind, text) }
    }

    /** Stops a task that's working: Claude is interrupted and the task waits for a new prompt. */
    fun stop(taskId: String) {
        val wasRunning = running.remove(taskId)?.also { it.cancel() } != null
        val task = store.get(taskId) ?: return
        if (!wasRunning && !task.isActive) return
        journal.update(taskId) { it.copy(status = TaskStatus.Stopped, interrupted = false, interruptedSinceMillis = null, pendingPrompt = null) }
        journal.status(taskId, "Stopped. Reply to give Claude a new direction.", notify = false)
        val sessionId = task.sessionId ?: return
        scope.launch(dispatcher) {
            withClaude { claude ->
                runCatching { claude.interrupt(sessionId) }
                spending.recordSessionCost(claude, taskId, sessionId)
            }
        }
    }

    /** Removes a task from the list. A running one is stopped first, and its Claude session is archived. */
    fun clear(taskId: String) {
        running.remove(taskId)?.cancel()
        val task = store.get(taskId) ?: return
        store.remove(taskId)
        seenEvents.remove(taskId)
        val sessionId = task.sessionId ?: return
        scope.launch(dispatcher) {
            withClaude { claude ->
                if (task.isActive) runCatching { claude.interrupt(sessionId) }
                runCatching { claude.archive(sessionId) }
            }
        }
    }

    /** Moves a task to the archive, keeping its chat. A task that's working is stopped first. */
    fun archive(taskId: String) {
        stop(taskId)
        journal.update(taskId) { it.copy(archived = true) }
    }

    /** Brings an archived task back to the main list. */
    fun unarchive(taskId: String) {
        journal.update(taskId) { it.copy(archived = false) }
    }

    /** Clears every finished task (stopped, failed, or done with no open pull request) that isn't archived. Returns how many. */
    fun clearFinished(): Int {
        val finished = store.all().filter { it.isFinished && !it.archived && !isRunning(it.id) }
        finished.forEach { clear(it.id) }
        return finished.size
    }

    /** One spoken sentence on where the task stands, for the voice assistant. */
    fun statusOf(taskId: String): String = store.get(taskId)?.let(::statusLine) ?: "That task is gone."

    /** One spoken sentence on where the task stands. */
    private fun statusLine(task: Task): String {
        val number = task.prNumber
        return when {
            isRunning(task.id) -> {
                val last = task.messages.lastOrNull { it.speaker != Speaker.You }?.text?.trimEnd('.')
                "Claude is working on it" + (last?.let { ". Latest: $it." } ?: ".")
            }
            task.interrupted -> "The connection dropped. I'm still trying to reconnect."
            task.status == TaskStatus.Failed -> "This task failed. " + (task.messages.lastOrNull { it.speaker == Speaker.Status }?.text ?: "")
            task.status == TaskStatus.Stopped -> "This task is stopped. Reply to give Claude a new direction."
            number == null -> if (task.status == TaskStatus.NeedsInput) "Claude is waiting for your answer." else "Claude finished, and there's no pull request."
            task.prState == PrState.Merged -> "Pull request $number is merged."
            task.prState == PrState.Closed -> "Pull request $number was closed without merging."
            task.conflicts -> "Pull request $number has merge conflicts. Say fix it to have Claude fix them."
            else -> when (task.checks?.state) {
                Checks.Failing -> "Checks failed on pull request $number. Say fix it to have Claude fix them."
                Checks.Pending -> "Checks are still running on pull request $number."
                Checks.Passing -> "Checks passed on pull request $number. Say merge it to merge."
                else -> "Pull request $number is open for review."
            }
        }
    }

    /** Refreshes the pull request and its checks, then asks the driver to confirm the merge. */
    private fun askToMerge(taskId: String) {
        if (store.get(taskId)?.prNumber == null) return journal.status(taskId, "There's no pull request to merge yet.")
        if (isRunning(taskId)) return journal.status(taskId, "Claude is still working. Say merge it once it's done.")
        pullRequests.refresh(taskId)
        val task = store.get(taskId) ?: return
        val number = task.prNumber
        val base = task.repo.defaultBranch
        when {
            task.prState == PrState.Merged -> journal.status(taskId, "Pull request $number is already merged.")
            task.prState == PrState.Closed -> journal.status(taskId, "Pull request $number is closed.")
            task.conflicts -> ask(taskId, PendingAction.FixConflicts, "Pull request $number has merge conflicts with $base, so it can't merge. Say yes to have Claude fix them.")
            else -> when (task.checks?.state) {
                Checks.Failing -> ask(taskId, PendingAction.FixChecks, "Checks failed on pull request $number, so I won't merge it. Say yes to have Claude fix them.")
                Checks.Pending -> journal.status(taskId, "Checks are still running on pull request $number. Ask again when they finish.")
                Checks.Passing -> ask(taskId, PendingAction.Merge, "Checks passed. Say yes to merge pull request $number into $base.")
                Checks.Unknown -> ask(taskId, PendingAction.Merge, "I can't read the checks on pull request $number. Say yes to merge it into $base anyway.")
                Checks.None, null -> ask(taskId, PendingAction.Merge, "No checks ran. Say yes to merge pull request $number into $base.")
            }
        }
    }

    private fun ask(taskId: String, action: PendingAction, question: String) {
        journal.update(taskId) { it.copy(pending = action) }
        journal.status(taskId, question)
    }

    /** Today's spend against the daily limit, for display. */
    fun spentTodayUsd(): Double = spending.spentTodayUsd()

    /** False once the daily or monthly spending limit is reached, so the car chat stops answering too. */
    fun canSpend(): Boolean = !spending.overLimit()

    // ---- Pull requests -------------------------------------------------------------------------

    /** True while some pull request's checks or merge are worth watching, so the app should stay awake. */
    fun isWatchingPullRequests(): Boolean {
        val now = clock()
        return mine().any { !isRunning(it.id) && pullRequests.needsWatching(it, now) }
    }

    /** Refreshes state and checks for every task with an open pull request, and merges green ones when auto-merge is on. */
    fun refreshPullRequests(): Job = scope.launch(dispatcher) {
        mine().filter { it.hasOpenPr && !isRunning(it.id) }.forEach { task ->
            currentCoroutineContext().ensureActive()
            runCatching { pullRequests.refresh(task.id) }
        }
    }

    /** Fetches one task's pull request and checks, says what changed, and merges it if it's ready. */
    fun refreshPullRequest(taskId: String) = pullRequests.refresh(taskId)

    /** Merges a task's pull request now, at the user's request. Returns a sentence saying what happened. */
    fun mergeNow(taskId: String): String = pullRequests.mergeNow(taskId)

    // ---- Running a turn ------------------------------------------------------------------------

    private inline fun withClaude(block: (AgentClient) -> Unit) {
        val key = config.anthropicKey() ?: return
        runCatching { claudeFactory(key) }.getOrNull()?.use(block)
    }

    private fun launchTurn(taskId: String) {
        running.remove(taskId)?.cancel()
        stuckAlerted -= taskId
        // Running a turn here makes this device the one that follows the task from now on.
        val me = config.deviceId()
        journal.update(taskId) { it.copy(status = TaskStatus.Working, interrupted = false, device = me ?: it.device) }
        val job = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            val key = config.anthropicKey()
            if (key == null) {
                journal.fail(taskId, "Add your Claude API key in Settings.")
                return@launch
            }
            var claude: AgentClient? = null
            try {
                val client = claudeFactory(key).also { claude = it }
                withRetries { prepare(client, taskId) }
                followUntilDone(client, taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Catch everything, not just exceptions, so a task never stays stuck on "Working" with nothing running.
                // Stopped or cleared by the user while a call was in flight: don't overwrite that with an error.
                if (currentCoroutineContext().isActive) {
                    runCatching { if (Failures.isTransient(e)) journal.interrupt(taskId, e) else journal.fail(taskId, Failures.describe(e)) }
                }
            } finally {
                runCatching { claude?.close() }
                running.remove(taskId, currentCoroutineContext()[Job])
            }
        }
        running[taskId] = job
        job.start()
    }

    /** Makes sure the task has a session and that Claude has every prompt. Safe to repeat. */
    private fun prepare(claude: AgentClient, taskId: String) {
        val task = store.get(taskId) ?: return
        if (task.sessionId == null || task.pendingPrompt != null) spending.checkBeforeSending()
        if (task.sessionId == null) {
            val token = config.githubToken() ?: throw IllegalStateException("Add a GitHub token in Settings.")
            val sessionId = try {
                claude.createSession(agentSetup(claude), task, token)
            } catch (e: Exception) {
                // The saved agent or environment is gone (deleted, or the key now points at another workspace).
                if (!Failures.isMissing(e)) throw e
                claude.createSession(claude.createSetup(config.agentProfile()).also(config::saveAgentSetup), task, token)
            }
            val request = task.messages.filter { it.speaker == Speaker.You }.joinToString("\n") { it.text }
            journal.update(taskId) { it.copy(sessionId = sessionId, pendingPrompt = ClaudeAgents.firstPrompt(it, request, config.repoSettings(it.repo).instructionLines)) }
            journal.step(taskId, Speaker.Status, "Claude is starting on ${task.repo.name}")
        }
        val current = store.get(taskId) ?: return
        val prompt = current.pendingPrompt ?: return
        spending.capSession(claude, current)
        claude.sendPrompt(current.sessionId!!, prompt)
        journal.update(taskId) { if (it.pendingPrompt == prompt) it.copy(pendingPrompt = null) else it }
    }

    /** The saved agent, or a new one when there's none yet or its settings no longer match what Settings asks for. */
    private fun agentSetup(claude: AgentClient): AgentSetup {
        val saved = config.agentSetup()
        val profile = config.agentProfile()
        if (saved != null && saved.profile == profile) return saved
        return claude.createSetup(profile, saved).also(config::saveAgentSetup)
    }

    /** Runs [block], retrying short outages with backoff before giving up. */
    private suspend fun withRetries(block: () -> Unit) {
        var attempt = 0
        while (true) {
            try {
                return runInterruptible { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!Failures.isTransient(e) || ++attempt >= SEND_ATTEMPTS) throw e
                sleep(Failures.backoffMillis(attempt))
            }
        }
    }

    private suspend fun followUntilDone(claude: AgentClient, taskId: String) {
        val sessionId = store.get(taskId)?.sessionId ?: return
        val seen = seenEvents.getOrPut(taskId) { ConcurrentHashMap.newKeySet() }
        var failures = 0
        var quiet = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            var heard = false
            val over = try {
                runInterruptible {
                    var halted = false
                    claude.follow(sessionId, seen, onConnected = { failures = 0 }, shouldStop = { halted }) { update ->
                        heard = true
                        // Once the turn is over for the app (a failure, or the spending limit), ignore the rest.
                        if (!halted && handle(claude, taskId, update)) halted = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Phones drop connections in tunnels and parking garages. Reconnect with backoff;
                // the session keeps running on Claude's side and its history fills the gap.
                if (!Failures.isTransient(e) || ++failures > MAX_RECONNECTS) throw e
                if (failures == 2) journal.progress(taskId, "Connection dropped. Reconnecting")
                sleep(Failures.backoffMillis(failures))
                continue
            }
            if (over) break
            // The server closed the stream mid-turn. Reconnect, but don't spin forever on a silent session.
            quiet = if (heard) 0 else quiet + 1
            if (quiet > MAX_QUIET_RECONNECTS) throw IllegalStateException("Claude stopped sending updates. Tap Try again to check on it.")
            sleep(QUIET_RECONNECT_MILLIS)
        }
        spending.recordSessionCost(claude, taskId, sessionId)
        if (store.get(taskId)?.status == TaskStatus.Working) afterTurn(taskId)
    }

    /** Applies one update from Claude's event stream. Returns true when the turn is over. */
    private fun handle(claude: AgentClient, taskId: String, update: AgentUpdate): Boolean = when (update) {
        is AgentUpdate.Progress -> { journal.progress(taskId, update.text); false }
        is AgentUpdate.Said -> { journal.step(taskId, Speaker.Claude, update.text); false }
        is AgentUpdate.Declined -> { journal.step(taskId, Speaker.Status, "Skipped a risky ${update.toolName} step that needs review"); false }
        is AgentUpdate.TurnDone -> true
        is AgentUpdate.Failed -> { journal.fail(taskId, update.reason); true }
        is AgentUpdate.BudgetReached -> { journal.fail(taskId, spending.limitMessage()); true }
        is AgentUpdate.Cost -> {
            spending.recordCost(taskId, update.totalUsd)
            if (spending.overLimit()) {
                // A backstop for the session's own budget: stop as soon as the day's limit is reached.
                store.get(taskId)?.sessionId?.let { id -> runCatching { claude.interrupt(id) } }
                journal.fail(taskId, spending.limitMessage())
                true
            } else {
                false
            }
        }
    }

    /** After a turn: open or refresh the pull request, then mark the task done or waiting. */
    private suspend fun afterTurn(taskId: String) {
        val task = store.get(taskId) ?: return
        journal.announceTurnResult(taskId)
        if (config.githubToken() != null) {
            if (task.prNumber == null) {
                try {
                    withRetries { pullRequests.open(taskId) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    journal.status(taskId, "Couldn't open the pull request. ${Failures.describe(e)}")
                }
            } else if (task.hasOpenPr) {
                pullRequests.rewatch(taskId)
            }
        }
        val question = store.get(taskId)?.messages?.lastOrNull { it.speaker == Speaker.Claude }?.text?.trimEnd()?.takeIf { it.endsWith("?") }
        journal.update(taskId) { it.copy(status = if (question != null) TaskStatus.NeedsInput else TaskStatus.Done, interruptedSinceMillis = null) }
        if (question != null) alert(taskId, AlertKind.Question, question)
    }

    companion object {
        private const val SEND_ATTEMPTS = 3
        private const val MAX_RECONNECTS = 8
        private const val MAX_QUIET_RECONNECTS = 30
        private const val QUIET_RECONNECT_MILLIS = 1_000L
        /** How long to wait between automatic tries for a task cut off by a lost connection. */
        private const val RESUME_INTERVAL_MILLIS = 30_000L
        /** How long to keep trying automatically before leaving it to the network callback or the Try again button. */
        private const val AUTO_RESUME_MILLIS = 2 * 60 * 60 * 1000L
        /** How long a working task may go without any update before the driver is told it looks stuck. */
        const val STUCK_MILLIS = 15 * 60 * 1000L

        /** Sent when the driver asks Claude to fix the checks that failed on its pull request. */
        fun fixChecksPrompt(task: Task): String {
            val failing = task.checks?.failing.orEmpty()
            return "The checks failed on pull request ${task.prNumber} after your last push" +
                (if (failing.isEmpty()) "." else ": ${failing.joinToString(", ")}.") +
                " You can't see their logs. Reproduce the failure by running the same build or tests here, fix it," +
                " commit, and push to the same branch."
        }

        /** Sent when the pull request conflicts with its base branch. */
        fun fixConflictsPrompt(task: Task): String {
            val base = task.repo.defaultBranch
            return "Pull request ${task.prNumber} has merge conflicts with $base. Fetch the latest $base and merge it into your branch." +
                " Resolve every conflict so both sides' changes still work, run the build and tests, commit, and push to the same branch."
        }

        /** Sent when the driver says "undo that". A revert keeps the history, so nothing is lost if they change their mind. */
        const val UNDO_PROMPT = "Undo your last change. If you committed it, revert that commit with git revert, run the build and tests," +
            " and push to the same branch. If it isn't committed yet, discard it instead. Then say in one sentence what you undid."

        /** Sent once the driver confirms "start over": the branch goes back to its base and the first request is done again. */
        fun startOverPrompt(task: Task): String {
            val base = task.repo.defaultBranch
            val request = task.messages.firstOrNull { it.speaker == Speaker.You }?.text.orEmpty()
            return "Start over. Throw away all your work on ${task.branch}: fetch the latest $base, reset the branch to it," +
                " and push with --force-with-lease. Then do the original request again from scratch, ignoring the approaches" +
                " you tried before:\n\n$request"
        }

        fun titleFrom(request: String): String {
            val words = request.trim().split(Regex("\\s+"))
            val title = words.take(8).joinToString(" ").trimEnd('.', ',', '!', '?')
            return title.replaceFirstChar { it.uppercase() } + if (words.size > 8) "…" else ""
        }

        fun slug(request: String): String =
            request.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                .split('-').filter { it.isNotEmpty() }.take(5).joinToString("-").ifEmpty { "task" }
    }
}
