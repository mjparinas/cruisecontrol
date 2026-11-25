package dev.aiauto.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stop, clear, spending limits, and pull request watching / auto-merge. */
class EngineControlsTest {
    private val repo = RepoRef("me", "app")

    private class MapStore : TaskStore {
        val map = LinkedHashMap<String, Task>()
        override fun get(id: String) = map[id]
        override fun all() = map.values.toList()
        override fun put(task: Task) { map[task.id] = task }
        override fun remove(id: String) { map.remove(id) }
    }

    private class Config : EngineConfig {
        var limit: Double? = null
        var spent = 0.0
        var monthLimit: Double? = null
        var monthSpent = 0.0
        var merge = false
        var fixChecks = false
        var fixConflicts = false
        var releaseSince: Long? = null
        var rules = ""
        /** Settings for repos other than the one most tests use, which takes the switches above. */
        val others = mutableMapOf<String, RepoSettings>()
        override fun anthropicKey() = "key"
        override fun githubToken() = "token"
        override fun agentSetup() = AgentSetup("agent", 1, "env", AgentProfile())
        override fun saveAgentSetup(setup: AgentSetup) {}
        override fun dailyLimitUsd() = limit
        override fun spentTodayUsd() = spent
        override fun monthlyLimitUsd() = monthLimit
        override fun spentThisMonthUsd() = monthSpent
        override fun addSpend(usd: Double) { spent += usd; monthSpent += usd }
        override fun repoSettings(repo: RepoRef) = others[repo.fullName] ?: RepoSettings(merge, fixChecks, fixConflicts, releaseSince, rules)
    }

    private class FakeClaude : AgentClient {
        /** Updates each follow() emits, in order; after they run out, a normal finished turn. */
        val turns = ArrayDeque<List<AgentUpdate>>()
        val sent = mutableListOf<String>()
        val interrupted = mutableListOf<String>()
        val archived = mutableListOf<String>()
        val budgets = mutableListOf<Double>()
        var finalCost: Double? = null

        override fun createSetup(profile: AgentProfile, existing: AgentSetup?) = AgentSetup("agent", 1, "env", AgentProfile())
        override fun createSession(setup: AgentSetup, task: Task, githubToken: String) = "session-${task.id}"
        var onSend: (String) -> Unit = {}
        override fun sendPrompt(sessionId: String, prompt: String) { sent += prompt; onSend(prompt) }
        override fun follow(
            sessionId: String,
            seenEventIds: MutableSet<String>,
            onConnected: () -> Unit,
            shouldStop: () -> Boolean,
            canReview: () -> Boolean,
            onUpdate: (AgentUpdate) -> Unit,
        ): Boolean {
            onConnected()
            (turns.removeFirstOrNull() ?: listOf(AgentUpdate.Said("Done."), AgentUpdate.TurnDone)).forEach { update ->
                if (shouldStop()) return@forEach
                if (update is AgentUpdate.NeedsApproval) reviewable += canReview()
                onUpdate(update)
                if (update is AgentUpdate.NeedsApproval) whileWaiting()
            }
            return true
        }
        /** What canReview() said for each flagged step, and the answers sent back. */
        val reviewable = mutableListOf<Boolean>()
        val confirmations = mutableListOf<Pair<String, Boolean>>()
        /** Runs while a flagged step waits, before the turn goes on. */
        var whileWaiting: () -> Unit = {}
        /** How many confirms should fail with a transient error before one is accepted. */
        var confirmFailuresLeft = 0
        override fun confirm(sessionId: String, toolUseId: String, allow: Boolean) {
            if (confirmFailuresLeft > 0) {
                confirmFailuresLeft--
                throw java.io.IOException("connection reset")
            }
            confirmations += toolUseId to allow
        }
        override fun interrupt(sessionId: String) { interrupted += sessionId }
        override fun archive(sessionId: String) { archived += sessionId }
        override fun setBudget(sessionId: String, maxUsd: Double) { budgets += maxUsd }
        override fun costUsd(sessionId: String) = finalCost
        override fun close() {}
    }

    private open class FakeGitHub : GitHub("token") {
        var branch = true
        var pr = PullRequest(7, "https://github.com/me/app/pull/7", nodeId = "PR_7", head = PullRequest.Head("abc"))
        var checksResult = ChecksSummary(Checks.Pending, total = 1)
        var autoMergeAllowed = false
        var reviewCommentsResult = emptyList<ReviewComment>()
        var openedPullBody: String? = null
        val merges = mutableListOf<Int>()
        var refuseMerge: String? = null
        val releases = mutableListOf<Pair<String, String>>()

        override fun tags(repo: RepoRef) = listOf("v0.4.4", "v0.3.9", "latest")
        override fun createRelease(repo: RepoRef, tag: String, target: String): String {
            releases += tag to target
            return tag
        }

        override fun branchExists(repo: RepoRef, branch: String) = this.branch
        override fun findPull(repo: RepoRef, branch: String): PullRequest? = null
        override fun openPull(repo: RepoRef, branch: String, title: String, body: String): PullRequest {
            openedPullBody = body
            return pr
        }
        override fun pull(repo: RepoRef, number: Int) = pr
        override fun reviewComments(repo: RepoRef, number: Int, afterId: Long?) =
            reviewCommentsResult.filter { afterId == null || it.id > afterId }.sortedBy { it.id }
        override fun checks(repo: RepoRef, sha: String) = checksResult
        override fun enableAutoMerge(pull: PullRequest) = autoMergeAllowed
        override fun merge(repo: RepoRef, number: Int): MergeResult {
            refuseMerge?.let { return MergeResult.Refused(it) }
            merges += number
            pr = pr.copy(merged = true, state = "closed")
            return MergeResult.Merged
        }
    }

    private val store = MapStore()
    private val config = Config()
    private val claude = FakeClaude()
    private val github = FakeGitHub()
    private val said = mutableListOf<String>()
    private val alerts = mutableListOf<AlertKind>()
    private var now = 1_000_000L

    private fun engine() = TaskEngine(
        store = store,
        config = config,
        scope = CoroutineScope(SupervisorJob()),
        clock = { now },
        claudeFactory = { claude },
        githubFactory = { github },
        onMessage = { _, m -> said += m.text },
        onAlert = { _, kind, text -> alerts += kind; said += text },
        sleep = {},
        dispatcher = Dispatchers.Unconfined,
    )

    @Test fun tracksCostPerTaskAndAddsItToToday() {
        claude.turns += listOf(AgentUpdate.Cost(0.40), AgentUpdate.Cost(1.25), AgentUpdate.TurnDone)
        claude.finalCost = 1.30
        val task = engine().start(repo, "add a test")
        assertEquals(1.30, store.get(task.id)!!.costUsd, 1e-9)
        assertEquals(1.30, config.spent, 1e-9)
    }

    @Test fun refusesToStartWhenTodaysLimitIsUsedUp() {
        config.limit = 5.0
        config.spent = 5.0
        val task = engine().start(repo, "anything")
        val failed = store.get(task.id)!!
        assertEquals(TaskStatus.Failed, failed.status)
        assertTrue(failed.lastMessage!!.text.contains("spending limit"))
        assertTrue(claude.sent.isEmpty())
    }

    @Test fun refusesToStartWhenThisMonthsLimitIsUsedUp() {
        config.limit = 20.0
        config.monthLimit = 50.0
        config.monthSpent = 50.0
        val task = engine().start(repo, "anything")
        val failed = store.get(task.id)!!
        assertEquals(TaskStatus.Failed, failed.status)
        assertTrue(failed.lastMessage!!.text.startsWith("Stopped at this month's \$50.00 spending limit"))
        assertTrue(claude.sent.isEmpty())
    }

    @Test fun canSpendUntilEitherLimitIsReached() {
        val engine = engine()
        config.limit = 10.0
        config.monthLimit = 100.0
        assertTrue(engine.canSpend())
        config.monthSpent = 100.0
        assertFalse(engine.canSpend())
        config.monthSpent = 0.0
        config.spent = 10.0
        assertFalse(engine.canSpend())
    }

    @Test fun capsTheSessionAtWhatIsLeftToday() {
        config.limit = 10.0
        config.spent = 4.0
        engine().start(repo, "anything")
        assertEquals(listOf(6.0), claude.budgets)
    }

    @Test fun stopsMidTurnWhenTheLimitIsReached() {
        config.limit = 2.0
        claude.turns += listOf(AgentUpdate.Cost(1.0), AgentUpdate.Cost(2.1), AgentUpdate.Said("never heard"), AgentUpdate.TurnDone)
        val task = engine().start(repo, "big refactor")
        val stopped = store.get(task.id)!!
        assertEquals(TaskStatus.Failed, stopped.status)
        assertEquals(listOf("session-${task.id}"), claude.interrupted)
        assertFalse(stopped.messages.any { it.text == "never heard" })
    }

    @Test fun budgetReachedOnClaudesSideSaysWhy() {
        config.limit = 3.0
        claude.turns += listOf(AgentUpdate.BudgetReached)
        val task = engine().start(repo, "x")
        assertTrue(store.get(task.id)!!.lastMessage!!.text.startsWith("Stopped at today's $3.00 spending limit"))
    }

    private val flagged = AgentUpdate.NeedsApproval("tool-1", "bash", "rm -rf build")

    @Test fun declinesFlaggedStepsWhileDriving() {
        claude.turns += listOf(flagged, AgentUpdate.Said("Done another way."), AgentUpdate.TurnDone)
        val task = engine().start(repo, "clean the build")
        assertEquals(listOf(false), claude.reviewable)
        assertEquals(listOf("tool-1" to false), claude.confirmations)
        assertTrue(store.get(task.id)!!.messages.any { it.text == "Skipped a risky bash step that needs review" })
        assertNull(store.get(task.id)!!.approval)
    }

    @Test fun holdsFlaggedStepsForTheDriverWhileParked() {
        val engine = engine()
        engine.setParked(true)
        claude.turns += listOf(flagged, AgentUpdate.Said("Cleaned it."), AgentUpdate.TurnDone)
        claude.whileWaiting = {
            val waiting = store.all().single()
            assertEquals(ToolApproval("tool-1", "bash", "rm -rf build"), waiting.approval)
            assertTrue(claude.confirmations.isEmpty())
            assertTrue("Claude wants to run bash: rm -rf build. Approve or deny it on the car screen." in said)
            engine.answerApproval(waiting.id, allow = true)
            assertNull(store.get(waiting.id)!!.approval)
        }
        val task = engine.start(repo, "clean the build")
        assertEquals(listOf(true), claude.reviewable)
        assertEquals(listOf("tool-1" to true), claude.confirmations)
        assertTrue(store.get(task.id)!!.messages.any { it.text == "Approved. Claude is carrying on." })
    }

    @Test fun driverCanDenyAFlaggedStep() {
        val engine = engine()
        engine.setParked(true)
        claude.turns += listOf(flagged, AgentUpdate.TurnDone)
        claude.whileWaiting = { engine.answerApproval(store.all().single().id, allow = false) }
        val task = engine.start(repo, "clean the build")
        assertEquals(listOf("tool-1" to false), claude.confirmations)
        assertTrue(store.get(task.id)!!.messages.any { it.text == "Denied. Claude will find another way." })
    }

    @Test fun drivingOffDeniesStepsStillWaiting() {
        val engine = engine()
        engine.setParked(true)
        claude.turns += listOf(flagged, AgentUpdate.TurnDone)
        claude.whileWaiting = {
            engine.setParked(false)
            // A late tap on Approve can't let it through once the car is moving.
            engine.answerApproval(store.all().single().id, allow = true)
        }
        val task = engine.start(repo, "clean the build")
        assertEquals(listOf("tool-1" to false), claude.confirmations)
        assertTrue(store.get(task.id)!!.messages.any { "because the car is moving" in it.text })
    }

    @Test fun aFailedApprovalConfirmPutsTheChoiceBack() {
        val engine = engine()
        engine.setParked(true)
        // Exhaust the three confirm retries so the choice is put back for another tap.
        claude.confirmFailuresLeft = 3
        claude.turns += listOf(flagged, AgentUpdate.Said("Cleaned it."), AgentUpdate.TurnDone)
        claude.whileWaiting = {
            engine.answerApproval(store.all().single().id, allow = true)
            val waiting = store.get(store.all().single().id)!!
            assertEquals(ToolApproval("tool-1", "bash", "rm -rf build"), waiting.approval)
            assertTrue(waiting.messages.any { "Couldn't send that to Claude" in it.text })
            assertTrue(claude.confirmations.isEmpty())
        }
        val task = engine.start(repo, "clean the build")
        assertTrue(claude.confirmations.isEmpty())
        assertTrue(store.get(task.id)!!.messages.any { "Couldn't send that to Claude" in it.text })
    }

    @Test fun approvalConfirmRetriesABriefOutageThenSucceeds() {
        val engine = engine()
        engine.setParked(true)
        // Two transient failures, then success — still inside one Approve tap (SEND_ATTEMPTS is 3).
        claude.confirmFailuresLeft = 2
        claude.turns += listOf(flagged, AgentUpdate.Said("Cleaned it."), AgentUpdate.TurnDone)
        claude.whileWaiting = { engine.answerApproval(store.all().single().id, allow = true) }
        val task = engine.start(repo, "clean the build")
        assertEquals(listOf("tool-1" to true), claude.confirmations)
        assertEquals(0, claude.confirmFailuresLeft)
        assertNull(store.get(task.id)!!.approval)
        assertTrue(store.get(task.id)!!.messages.any { it.text == "Approved. Claude is carrying on." })
    }

    @Test fun opensAMissingPullRequestOnRefresh() {
        github.branch = false
        val engine = engine()
        val task = engine.start(repo, "fix it")
        assertNull(store.get(task.id)!!.prNumber)
        assertEquals(TaskStatus.Done, store.get(task.id)!!.status)
        assertTrue(engine.isWatchingPullRequests())

        github.branch = true
        engine.refreshPullRequests()
        assertEquals(7, store.get(task.id)!!.prNumber)
        assertTrue(said.any { "Pull request 7 is open" in it })
    }

    @Test fun onlyAZeroSpeedCountsAsStandingStill() {
        assertTrue(TaskEngine.isStandingStill(0f))
        assertFalse(TaskEngine.isStandingStill(3.5f))
        assertFalse(TaskEngine.isStandingStill(-1f))
        assertFalse(TaskEngine.isStandingStill(null))
    }

    @Test fun describesAStepWithoutDetail() {
        assertEquals("run a web_fetch step", ToolApproval("t", "web_fetch").summary)
    }

    @Test fun stopInterruptsAndClearArchives() {
        val engine = engine()
        val now = 0L
        store.put(Task("t1", repo, "x", "b", sessionId = "s1", status = TaskStatus.Working, createdMillis = now, updatedMillis = now))
        engine.stop("t1")
        assertEquals(TaskStatus.Stopped, store.get("t1")!!.status)
        assertEquals(listOf("s1"), claude.interrupted)
        assertTrue(store.get("t1")!!.isFinished)

        store.put(Task("t2", repo, "y", "b", sessionId = "s2", status = TaskStatus.Done, prNumber = 3, prState = PrState.Open, createdMillis = now, updatedMillis = now))
        assertEquals(1, engine.clearFinished())
        assertNull(store.get("t1"))
        assertEquals(listOf("s1"), claude.archived)
        // A done task whose pull request is still open stays, so it can be merged from the car.
        assertTrue(store.get("t2") != null)
    }

    @Test fun archiveStopsAndKeepsTheTaskOutOfClearFinished() {
        val engine = engine()
        store.put(Task("t", repo, "x", "b", sessionId = "s", status = TaskStatus.Working, createdMillis = 0, updatedMillis = 0))
        engine.archive("t")
        val archived = store.get("t")!!
        assertTrue(archived.archived)
        assertEquals(TaskStatus.Stopped, archived.status)
        assertEquals(listOf("s"), claude.interrupted)
        assertEquals(0, engine.clearFinished())
        assertTrue(store.get("t") != null)

        engine.unarchive("t")
        assertFalse(store.get("t")!!.archived)
        engine.archive("t")
        engine.prompt("t", "one more thing")
        assertFalse(store.get("t")!!.archived)
    }

    @Test fun clearRemovesAnArchivedTask() {
        val engine = engine()
        store.put(Task("t", repo, "x", "b", sessionId = "s", status = TaskStatus.Done, archived = true, createdMillis = 0, updatedMillis = 0))
        engine.clear("t")
        assertNull(store.get("t"))
        assertEquals(listOf("s"), claude.archived)
        assertTrue(claude.interrupted.isEmpty())
    }

    @Test fun stopDoesNothingToAFinishedTask() {
        val engine = engine()
        store.put(Task("t", repo, "x", "b", sessionId = "s", status = TaskStatus.Done, createdMillis = 0, updatedMillis = 0))
        engine.stop("t")
        assertEquals(TaskStatus.Done, store.get("t")!!.status)
        assertTrue(claude.interrupted.isEmpty())
    }

    @Test fun opensThePrAndTurnsOnGitHubAutoMergeWhenAllowed() {
        config.merge = true
        github.autoMergeAllowed = true
        val task = engine().start(repo, "fix it")
        val done = store.get(task.id)!!
        assertEquals(7, done.prNumber)
        assertEquals(PrState.Open, done.prState)
        assertTrue(done.githubAutoMerge)
        assertTrue(said.any { it.contains("GitHub will merge it when checks pass") })
    }

    @Test fun issueTasksMentionTheIssueInThePullRequestBody() {
        val issue = GitHubIssue(34, "Fix login", "2026-01-02T03:04:05Z", body = "Login fails after sign out.")
        val task = engine().startIssue(repo, issue)
        assertEquals(34, store.get(task.id)!!.issueNumber)
        assertTrue(github.openedPullBody!!.contains("Closes #34"))
        assertTrue(github.openedPullBody!!.contains("Fix issue #34: Fix login"))
    }

    @Test fun announcesEachNewReviewCommentOnce() {
        github.reviewCommentsResult = listOf(ReviewComment(10, "Existing", user = ReviewComment.User("reviewer")))
        val engine = engine()
        val task = engine.start(repo, "fix it")
        assertEquals(10, store.get(task.id)!!.lastReviewCommentId)
        assertTrue(alerts.isEmpty())

        github.reviewCommentsResult = listOf(
            ReviewComment(10, "Existing", user = ReviewComment.User("reviewer")),
            ReviewComment(11, "Please rename this.", "src/Main.kt", ReviewComment.User("reviewer")),
        )
        engine.refreshPullRequest(task.id)
        engine.refreshPullRequest(task.id)

        assertEquals(11, store.get(task.id)!!.lastReviewCommentId)
        assertEquals(listOf(AlertKind.ReviewComment), alerts)
        assertTrue(said.any { it.contains("reviewer commented on pull request 7 on src/Main.kt: Please rename this.") })
    }

    @Test fun aReplyToAReviewCommentIncludesTheCommentInThePrompt() {
        val engine = engine()
        val task = engine.start(repo, "fix it")
        github.reviewCommentsResult = listOf(ReviewComment(11, "Please rename this.", "src/Main.kt", ReviewComment.User("reviewer")))
        engine.refreshPullRequest(task.id)

        engine.prompt(task.id, "I'll rename it.")

        assertTrue(claude.sent.last().contains("reviewer commented on pull request 7 on src/Main.kt: Please rename this."))
        assertTrue(claude.sent.last().contains("The driver's response is:\nI'll rename it."))
        assertNull(store.get(task.id)!!.pendingReviewComment)
    }

    @Test fun mergesOnceChecksPassWhenGitHubAutoMergeIsUnavailable() {
        config.merge = true
        val engine = engine()
        val task = engine.start(repo, "fix it")
        assertTrue(said.any { it.contains("I'll merge it when checks pass") })
        assertTrue(engine.isWatchingPullRequests())

        engine.refreshPullRequest(task.id)
        assertEquals(Checks.Pending, store.get(task.id)!!.checks?.state)
        assertTrue(github.merges.isEmpty())

        github.checksResult = ChecksSummary(Checks.Passing, total = 2)
        engine.refreshPullRequest(task.id)
        assertEquals(listOf(7), github.merges)
        assertEquals(PrState.Merged, store.get(task.id)!!.prState)
        assertTrue(said.any { it == "Checks passed on pull request 7. Merging it now." })
        assertTrue(said.any { it == "Merged pull request 7." })
        assertFalse(engine.isWatchingPullRequests())
        assertTrue(store.get(task.id)!!.isFinished)
    }

    @Test fun mergesOnlyInReposWhereMergeWhenChecksPassIsOn() {
        config.merge = true
        val other = RepoRef("me", "website")
        config.others[other.fullName] = RepoSettings(autoMerge = false)
        val engine = engine()
        val here = engine.start(repo, "fix it")
        assertTrue(said.any { it.contains("I'll merge it when checks pass") })
        val there = engine.start(other, "fix it too")
        assertTrue(said.any { it.contains("is open for review") })

        github.checksResult = ChecksSummary(Checks.Passing, total = 1)
        engine.refreshPullRequest(there.id)
        assertTrue(github.merges.isEmpty())
        assertEquals(PrState.Open, store.get(there.id)!!.prState)
        engine.refreshPullRequest(here.id)
        assertEquals(listOf(7), github.merges)
    }

    @Test fun fixesFailingChecksOnlyInReposThatAllowIt() {
        val other = RepoRef("me", "website")
        config.others[other.fullName] = RepoSettings(autoMerge = false, autoFixChecks = true)
        val engine = engine()
        val manual = engine.start(repo, "fix it")
        github.checksResult = ChecksSummary(Checks.Failing, failing = listOf("build"), total = 1)
        engine.refreshPullRequest(manual.id)
        assertEquals(PendingAction.FixChecks, store.get(manual.id)!!.pending)
        assertEquals(0, store.get(manual.id)!!.autoFixes)

        github.checksResult = ChecksSummary(Checks.Pending, total = 1)
        val automatic = engine.start(other, "fix it too")
        github.checksResult = ChecksSummary(Checks.Failing, failing = listOf("build"), total = 1)
        engine.refreshPullRequest(automatic.id)
        assertEquals(1, store.get(automatic.id)!!.autoFixes)
        assertTrue(said.any { it.endsWith("Asking Claude to fix them.") })
    }

    @Test fun sendsTheRepoRulesWithTheFirstPromptOnly() {
        config.rules = "Run npm test before pushing\n\n  Never touch the docs folder  "
        engine().start(repo, "add a toggle")
        val first = claude.sent.first()
        assertTrue("- Run npm test before pushing\n- Never touch the docs folder\n\nRequest" in first)

        claude.sent.clear()
        config.others[RepoRef("me", "website").fullName] = RepoSettings()
        engine().start(RepoRef("me", "website"), "add a toggle")
        assertFalse("Rules from the user for this repository" in claude.sent.first())
    }

    @Test fun releasesAPullRequestMergedByHandWhenAutoReleaseIsOn() {
        config.releaseSince = now - 1
        val engine = engine()
        val task = engine.start(repo, "fix it")
        github.checksResult = ChecksSummary(Checks.Passing, total = 1)
        engine.refreshPullRequest(task.id)
        assertTrue(engine.isWatchingPullRequests())

        github.pr = github.pr.copy(merged = true, state = "closed", mergedAt = java.time.Instant.ofEpochMilli(now).toString(), mergeCommitSha = "def")
        engine.refreshPullRequest(task.id)
        engine.refreshPullRequest(task.id)
        assertEquals(listOf("v0.4.5" to "def"), github.releases)
        assertEquals("v0.4.5", store.get(task.id)!!.releaseTag)
        assertTrue(said.any { it == "Released v0.4.5." })
    }

    @Test fun releasesAfterTheAppMergesWhenAutoReleaseIsOn() {
        config.merge = true
        config.releaseSince = now - 1
        val engine = engine()
        val task = engine.start(repo, "fix it")
        github.checksResult = ChecksSummary(Checks.Passing, total = 1)
        engine.refreshPullRequest(task.id)
        assertEquals(listOf(7), github.merges)
        assertEquals(listOf("v0.4.5" to repo.defaultBranch), github.releases)
    }

    @Test fun noReleaseWhenOffOrMergedBeforeItWasOn() {
        val engine = engine()
        val task = engine.start(repo, "fix it")
        github.pr = github.pr.copy(merged = true, state = "closed", mergedAt = java.time.Instant.ofEpochMilli(now).toString())
        engine.refreshPullRequest(task.id)
        assertTrue(github.releases.isEmpty())

        github.pr = github.pr.copy(merged = false, state = "open")
        val other = engine.start(repo, "another")
        engine.refreshPullRequest(other.id)
        config.releaseSince = now + 1
        github.pr = github.pr.copy(merged = true, state = "closed")
        engine.refreshPullRequest(other.id)
        assertTrue(github.releases.isEmpty())
        assertNull(store.get(other.id)!!.releaseTag)
    }

    @Test fun neverMergesFailingOrUnreadableChecks() {
        config.merge = true
        val engine = engine()
        val task = engine.start(repo, "fix it")
        github.checksResult = ChecksSummary(Checks.Failing, failing = listOf("build"), total = 2)
        engine.refreshPullRequest(task.id)
        assertTrue(said.last().contains("Checks failed on pull request 7: build"))
        github.checksResult = ChecksSummary(Checks.Unknown)
        now += 60 * 60 * 1000L
        engine.refreshPullRequest(task.id)
        assertTrue(github.merges.isEmpty())
    }

    @Test fun watchesAgainAfterAFollowUpPushesAFix() {
        config.merge = true
        val engine = engine()
        val task = engine.start(repo, "fix it")
        github.checksResult = ChecksSummary(Checks.Failing, failing = listOf("build"), total = 1)
        engine.refreshPullRequest(task.id)
        now += 4 * 60 * 60 * 1000L
        assertFalse(engine.isWatchingPullRequests())

        // Claude pushes a fix in a follow-up turn; the new head's checks are running.
        github.pr = github.pr.copy(head = PullRequest.Head("def"))
        github.checksResult = ChecksSummary(Checks.Pending, total = 1)
        engine.prompt(task.id, "fix the build")
        assertEquals(Checks.Pending, store.get(task.id)!!.checks?.state)
        assertTrue(engine.isWatchingPullRequests())

        github.checksResult = ChecksSummary(Checks.Passing, total = 1)
        engine.refreshPullRequest(task.id)
        assertEquals(listOf(7), github.merges)
    }

    @Test fun waitsForChecksToAppearBeforeMergingARepoWithoutCi() {
        config.merge = true
        github.checksResult = ChecksSummary(Checks.None)
        val engine = engine()
        val task = engine.start(repo, "fix it")
        engine.refreshPullRequest(task.id)
        assertTrue(github.merges.isEmpty())
        now += 11 * 60 * 1000L
        engine.refreshPullRequest(task.id)
        assertEquals(listOf(7), github.merges)
    }

    @Test fun sendsFailingChecksBackToClaudeWhenAutoFixIsOn() {
        config.fixChecks = true
        val engine = engine()
        val task = engine.start(repo, "add a feature")
        github.checksResult = ChecksSummary(Checks.Failing, failing = listOf("unit tests"), total = 1)
        engine.refreshPullRequest(task.id)
        assertTrue(said.any { it == "Checks failed on pull request 7: unit tests. Asking Claude to fix them." })
        assertTrue(claude.sent.last().contains("The checks failed on pull request 7"))
        assertNull(store.get(task.id)!!.pending)
    }

    @Test fun asksBeforeFixingChecksWhenAutoFixIsOff() {
        val engine = engine()
        val task = engine.start(repo, "add a feature")
        github.checksResult = ChecksSummary(Checks.Failing, failing = listOf("unit tests"), total = 1)
        engine.refreshPullRequest(task.id)
        assertEquals(1, claude.sent.size)
        assertEquals(PendingAction.FixChecks, store.get(task.id)!!.pending)
    }

    @Test fun stopsFixingChecksAutomaticallyAfterThreeTries() {
        config.fixChecks = true
        val engine = engine()
        val task = engine.start(repo, "add a feature")
        repeat(4) {
            github.checksResult = ChecksSummary(Checks.Pending, total = 1)
            engine.refreshPullRequest(task.id)
            github.checksResult = ChecksSummary(Checks.Failing, failing = listOf("build"), total = 1)
            engine.refreshPullRequest(task.id)
        }
        // The first request plus three automatic fixes, then the app asks instead.
        assertEquals(4, claude.sent.size)
        assertEquals(PendingAction.FixChecks, store.get(task.id)!!.pending)
        assertTrue(said.last().contains("Say yes to have Claude fix them"))
    }

    @Test fun resolvesMergeConflictsAutomaticallyThenMerges() {
        config.merge = true
        config.fixConflicts = true
        val engine = engine()
        val task = engine.start(repo, "add a feature")
        github.pr = github.pr.copy(mergeable = false, mergeableState = "dirty")
        github.checksResult = ChecksSummary(Checks.Passing, total = 1)
        // Claude's push clears the conflict.
        claude.onSend = { if ("merge conflicts" in it) github.pr = github.pr.copy(mergeable = true, mergeableState = "clean") }
        engine.refreshPullRequest(task.id)
        assertTrue(said.any { it == "Pull request 7 has merge conflicts with main. Asking Claude to fix them." })
        assertTrue(claude.sent.last().contains("merge conflicts with main"))
        assertEquals(listOf(7), github.merges)
    }

    @Test fun neverMergesAConflictingPullRequest() {
        config.merge = true
        val engine = engine()
        val task = engine.start(repo, "add a feature")
        github.pr = github.pr.copy(mergeable = false, mergeableState = "dirty")
        github.checksResult = ChecksSummary(Checks.Passing, total = 1)
        engine.refreshPullRequest(task.id)
        assertTrue(github.merges.isEmpty())
        assertEquals(PendingAction.FixConflicts, store.get(task.id)!!.pending)
        assertEquals(1, claude.sent.size)
        engine.prompt(task.id, "yes")
        assertTrue(claude.sent.last().contains("merge conflicts with main"))
    }

    @Test fun aConflictAndFailingChecksKeepTheConflictQuestion() {
        val engine = engine()
        val task = engine.start(repo, "add a feature")
        github.pr = github.pr.copy(mergeable = false, mergeableState = "dirty")
        github.checksResult = ChecksSummary(Checks.Failing, failing = listOf("build"), total = 1)
        engine.refreshPullRequest(task.id)
        assertEquals(PendingAction.FixConflicts, store.get(task.id)!!.pending)
        assertTrue(said.any { "merge conflicts" in it })
        assertTrue(said.none { "Say yes to have Claude fix them." in it && "Checks failed" in it })
    }

    @Test fun reportsARefusedMergeOnlyOnce() {
        config.merge = true
        github.refuseMerge = "Pull request is not mergeable"
        github.checksResult = ChecksSummary(Checks.Passing, total = 1)
        val engine = engine()
        val task = engine.start(repo, "fix it")
        engine.refreshPullRequest(task.id)
        engine.refreshPullRequest(task.id)
        assertEquals(1, said.count { it.startsWith("Couldn't merge pull request 7") })
    }

    @Test fun withoutAutoMergeItOnlyReports() {
        val engine = engine()
        val task = engine.start(repo, "fix it")
        github.checksResult = ChecksSummary(Checks.Passing, total = 1)
        engine.refreshPullRequest(task.id)
        assertTrue(github.merges.isEmpty())
        assertEquals("Checks passed on pull request 7.", said.last())
        github.pr = github.pr.copy(merged = true, state = "closed")
        engine.refreshPullRequest(task.id)
        assertEquals("Pull request 7 was merged.", said.last())
    }

    @Test fun manualMergeTurnsOnAutoMergeWhenChecksAreStillRunning() {
        val gh = object : FakeGitHub() {
            override fun merge(repo: RepoRef, number: Int) = MergeResult.Refused("Required status check \"build\" is expected.")
        }
        gh.autoMergeAllowed = true
        assertTrue(Projects.mergeMessage(gh, repo, 7).contains("turned on auto-merge"))
        gh.autoMergeAllowed = false
        assertEquals("Couldn't merge pull request 7. Required status check \"build\" is expected.", Projects.mergeMessage(gh, repo, 7))
    }

    @Test fun speaksARepoSummary() {
        val status = RepoStatus(
            repo,
            CommitInfo("abc", "Add **dark** mode", "Mel", now - 2 * 60 * 60 * 1000L),
            ChecksSummary(Checks.Passing, total = 3),
            listOf(PullStatus(PullRequest(5, "u", title = "Fix signing"), ChecksSummary(Checks.Failing, listOf("build"), 2))),
        )
        assertEquals(
            "app. Main has checks passing. Last change: Add dark mode, 2 hours ago. One open pull request. Number 5, Fix signing, build failing.",
            SpokenStatus.forRepo(status, now),
        )
        assertEquals("$1.30", SpokenStatus.money(1.3))
    }

    @Test fun alertsWhenChecksFailAndNobodyIsFixingThem() {
        val engine = engine()
        val task = engine.start(repo, "fix it")
        github.checksResult = ChecksSummary(Checks.Failing, failing = listOf("build"), total = 1)
        engine.refreshPullRequest(task.id)
        assertEquals(listOf(AlertKind.ChecksFailed), alerts)
    }

    @Test fun noAlertWhileClaudeFixesChecksItself() {
        config.fixChecks = true
        val engine = engine()
        val task = engine.start(repo, "fix it")
        github.checksResult = ChecksSummary(Checks.Failing, failing = listOf("build"), total = 1)
        engine.refreshPullRequest(task.id)
        assertTrue(AlertKind.ChecksFailed !in alerts)
    }

    @Test fun alertsWhenClaudeAsksAQuestion() {
        claude.turns += listOf(AgentUpdate.Said("Should I also update the docs?"), AgentUpdate.TurnDone)
        engine().start(repo, "rename the setting")
        assertEquals(listOf(AlertKind.Question), alerts)
    }

    @Test fun anEarlierTurnsQuestionIsNotAskedAgain() {
        claude.turns += listOf(AgentUpdate.Said("Should I also update the docs?"), AgentUpdate.TurnDone)
        claude.turns += listOf(AgentUpdate.Progress("Editing files"), AgentUpdate.TurnDone)
        val engine = engine()
        val task = engine.start(repo, "rename the setting")
        assertEquals(TaskStatus.NeedsInput, store.get(task.id)!!.status)
        engine.prompt(task.id, "yes, update the readme as well")
        assertEquals(TaskStatus.Done, store.get(task.id)!!.status)
        assertEquals(listOf(AlertKind.Question), alerts)
    }

    @Test fun gitHubAutoMergeIsNotAnnouncedAsMerged() {
        config.merge = true
        val autoMerging = object : FakeGitHub() {
            override fun merge(repo: RepoRef, number: Int) = MergeResult.AutoMergeOn
        }
        val engine = TaskEngine(
            store = store, config = config, scope = CoroutineScope(SupervisorJob()), clock = { now },
            claudeFactory = { claude }, githubFactory = { autoMerging }, onMessage = { _, m -> said += m.text },
            sleep = {}, dispatcher = Dispatchers.Unconfined,
        )
        val task = engine.start(repo, "fix it")
        autoMerging.checksResult = ChecksSummary(Checks.Passing, total = 1)
        engine.refreshPullRequest(task.id)
        val after = store.get(task.id)!!
        assertEquals(PrState.Open, after.prState)
        assertTrue(after.githubAutoMerge)
        assertTrue("GitHub will merge pull request 7 when checks pass." in said)
        assertFalse("Merged pull request 7." in said)
    }
}
