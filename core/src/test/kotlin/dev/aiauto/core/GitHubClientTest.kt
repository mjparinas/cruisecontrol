package dev.aiauto.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real GitHub client against canned HTTP responses. The engine tests fake this class away,
 * but its answers decide whether a pull request counts as green and gets merged.
 */
class GitHubClientTest {
    private val repo = RepoRef("me", "app")

    private data class Sent(val method: String, val path: String, val body: String?)

    private val responses = mutableMapOf<String, ArrayDeque<Pair<Int, String>>>()
    private val sent = mutableListOf<Sent>()

    /** Queues a reply for "METHOD path". The last reply for a key keeps being returned. */
    private fun on(method: String, path: String, code: Int, body: String) {
        responses.getOrPut("$method $path") { ArrayDeque() } += code to body
    }

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val path = request.url.toString().removePrefix("https://api.github.com")
        val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }
        sent += Sent(request.method, path, body)
        val queue = responses["${request.method} $path"] ?: throw AssertionError("Unexpected request: ${request.method} $path")
        val (code, text) = if (queue.size > 1) queue.removeFirst() else queue.first()
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("HTTP $code")
            .body(text.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    private val github = GitHub("token", http, pause = {})

    private fun runs(vararg runs: String) = """{"total_count": ${runs.size}, "check_runs": [${runs.joinToString(",")}]}"""
    private fun run(name: String, status: String, conclusion: String? = null) =
        """{"name": "$name", "status": "$status", "conclusion": ${conclusion?.let { "\"$it\"" } ?: "null"}}"""
    private fun statuses(vararg items: Pair<String, String>) =
        """{"state": "x", "total_count": ${items.size}, "statuses": [${items.joinToString(",") { """{"context": "${it.first}", "state": "${it.second}"}""" }}]}"""

    private fun checkRuns(code: Int, body: String) = on("GET", "/repos/me/app/commits/abc/check-runs?per_page=100", code, body)
    private fun commitStatus(code: Int, body: String) = on("GET", "/repos/me/app/commits/abc/status", code, body)

    // ---- Checks ---------------------------------------------------------------------------------------------

    @Test fun checksPassWhenEveryRunAndStatusSucceeded() {
        checkRuns(200, runs(run("build", "completed", "success"), run("lint", "completed", "neutral"), run("docs", "completed", "skipped")))
        commitStatus(200, statuses("ci/deploy" to "success"))
        assertEquals(ChecksSummary(Checks.Passing, total = 4), github.checks(repo, "abc"))
    }

    @Test fun anyFailureMakesChecksFailingEvenWhileOthersRun() {
        checkRuns(
            200,
            runs(
                run("build", "completed", "failure"),
                run("e2e", "completed", "timed_out"),
                run("lint", "completed", "cancelled"),
                run("test", "in_progress"),
            ),
        )
        commitStatus(200, statuses("ci/deploy" to "error", "ci/other" to "success"))
        val summary = github.checks(repo, "abc")
        assertEquals(Checks.Failing, summary.state)
        assertEquals(listOf("build", "e2e", "lint", "ci/deploy"), summary.failing)
        assertEquals(6, summary.total)
    }

    @Test fun checksStillRunningArePending() {
        checkRuns(200, runs(run("build", "queued"), run("lint", "completed", "success")))
        commitStatus(200, statuses("ci/deploy" to "pending"))
        assertEquals(ChecksSummary(Checks.Pending, total = 3), github.checks(repo, "abc"))
    }

    @Test fun aRunThatFinishedWithoutAPassingConclusionIsNotPassing() {
        checkRuns(200, runs(run("build", "completed", "action_required"), run("lint", "completed")))
        commitStatus(200, statuses())
        assertEquals(ChecksSummary(Checks.Failing, listOf("build", "lint"), 2), github.checks(repo, "abc"))
    }

    @Test fun aCommitWithNoChecksHasNone() {
        checkRuns(200, runs())
        commitStatus(200, statuses())
        assertEquals(ChecksSummary(Checks.None), github.checks(repo, "abc"))
    }

    @Test fun reportsEachFailingCheckOnce() {
        checkRuns(200, runs(run("build", "completed", "failure"), run("build", "completed", "failure")))
        commitStatus(200, statuses("build" to "failure"))
        assertEquals(listOf("build"), github.checks(repo, "abc").failing)
    }

    @Test fun checksTheTokenCannotReadAreUnknownNotPassing() {
        checkRuns(403, """{"message": "Resource not accessible by personal access token"}""")
        commitStatus(404, """{"message": "Not Found"}""")
        assertEquals(Checks.Unknown, github.checks(repo, "abc").state)
    }

    @Test fun usesWhicheverChecksTheTokenCanRead() {
        checkRuns(403, """{"message": "Resource not accessible by personal access token"}""")
        commitStatus(200, statuses("ci/build" to "failure"))
        assertEquals(ChecksSummary(Checks.Failing, listOf("ci/build"), 1), github.checks(repo, "abc"))
    }

    @Test fun aServerErrorWhileReadingChecksIsAnErrorNotNoChecks() {
        // Treating this as "no checks" would let a repo without CI auto-merge unchecked work.
        checkRuns(502, "Bad gateway")
        commitStatus(200, statuses())
        val e = assertFailsWith<GitHubException> { github.checks(repo, "abc") }
        assertEquals(502, e.code)
    }

    // ---- Merging --------------------------------------------------------------------------------------------

    private val mergePath = "/repos/me/app/pulls/7/merge"
    private fun mergeMethods() = sent.filter { it.path == mergePath }.map { Regex("\"merge_method\":\"(\\w+)\"").find(it.body!!)!!.groupValues[1] }

    @Test fun mergesWithASquashFirst() {
        on("PUT", mergePath, 200, """{"merged": true}""")
        assertEquals(MergeResult.Merged, github.merge(repo, 7))
        assertEquals(listOf("squash"), mergeMethods())
    }

    @Test fun triesTheNextMergeMethodWhenTheRepoTurnsOneOff() {
        on("PUT", mergePath, 405, """{"message": "Squash merges are not allowed on this repository."}""")
        on("PUT", mergePath, 200, """{"merged": true}""")
        assertEquals(MergeResult.Merged, github.merge(repo, 7))
        assertEquals(listOf("squash", "merge"), mergeMethods())
    }

    @Test fun refusesWithTheLastReasonWhenNoMergeMethodIsAllowed() {
        on("PUT", mergePath, 405, """{"message": "Squash merges are not allowed on this repository."}""")
        on("PUT", mergePath, 405, """{"message": "Merge commits are not allowed on this repository."}""")
        on("PUT", mergePath, 405, """{"message": "Rebase merges are not allowed on this repository."}""")
        assertEquals(MergeResult.Refused("Rebase merges are not allowed on this repository."), github.merge(repo, 7))
        assertEquals(listOf("squash", "merge", "rebase"), mergeMethods())
    }

    @Test fun refusesWithoutTryingOtherMethodsWhenThePullRequestCannotMerge() {
        on("PUT", mergePath, 405, """{"message": "Pull Request is not mergeable"}""")
        assertEquals(MergeResult.Refused("Pull Request is not mergeable"), github.merge(repo, 7))
        assertEquals(listOf("squash"), mergeMethods())
    }

    @Test fun refusesWhenTheHeadMovedOrRequiredChecksAreMissing() {
        on("PUT", mergePath, 409, """{"message": "Head branch was modified. Review and try the merge again."}""")
        assertEquals(MergeResult.Refused("Head branch was modified. Review and try the merge again."), github.merge(repo, 7))
        responses.clear()
        on("PUT", mergePath, 422, "")
        assertEquals(MergeResult.Refused("HTTP 422"), github.merge(repo, 7))
    }

    @Test fun aRejectedTokenIsAnErrorNotARefusal() {
        on("PUT", mergePath, 401, """{"message": "Bad credentials"}""")
        val e = assertFailsWith<GitHubException> { github.merge(repo, 7) }
        assertEquals(401, e.code)
        assertEquals("GitHub: Bad credentials", e.message)
        assertEquals("GitHub rejected your token. Update it in Settings.", Failures.describe(e))
    }

    // ---- GitHub's auto-merge --------------------------------------------------------------------------------

    private val pr = PullRequest(7, "https://github.com/me/app/pull/7", nodeId = "PR_kw7")

    @Test fun turnsOnAutoMergeThroughGraphQl() {
        on("POST", "/graphql", 200, """{"data": {"enablePullRequestAutoMerge": {"clientMutationId": null}}}""")
        assertTrue(github.enableAutoMerge(pr))
        val body = sent.single().body!!
        assertTrue("enablePullRequestAutoMerge" in body)
        assertTrue("\"id\":\"PR_kw7\"" in body)
    }

    @Test fun autoMergeIsOffWhenGitHubAnswersWithErrors() {
        // GraphQL reports "auto-merge isn't allowed" and "no required checks" as 200 with errors.
        on("POST", "/graphql", 200, """{"data": null, "errors": [{"message": "Pull request is in clean status"}]}""")
        assertFalse(github.enableAutoMerge(pr))
    }

    @Test fun autoMergeNeedsANodeId() {
        assertFalse(github.enableAutoMerge(pr.copy(nodeId = "")))
        assertTrue(sent.isEmpty())
    }

    @Test fun autoMergeIsOffWhenTheTokenLacksPermission() {
        on("POST", "/graphql", 403, """{"message": "Forbidden"}""")
        assertFalse(github.enableAutoMerge(pr))
    }

    @Test fun autoMergeSurfacesABadTokenOrAnOutage() {
        on("POST", "/graphql", 401, """{"message": "Bad credentials"}""")
        assertEquals(401, assertFailsWith<GitHubException> { github.enableAutoMerge(pr) }.code)
        responses.clear()
        on("POST", "/graphql", 503, "")
        assertEquals(503, assertFailsWith<GitHubException> { github.enableAutoMerge(pr) }.code)
    }

    // ---- Pull requests --------------------------------------------------------------------------------------

    private fun pullJson(extra: String) =
        """{"number": 7, "html_url": "https://github.com/me/app/pull/7", "node_id": "PR_kw7", "head": {"sha": "abc", "ref": "b"}, "unknown_field": 1, $extra}"""

    @Test fun readsAnOpenPullRequestStillBeingCheckedForConflicts() {
        on("GET", "/repos/me/app/pulls/7", 200, pullJson(""""state": "open", "mergeable": null, "auto_merge": null"""))
        val pull = github.pull(repo, 7)
        assertEquals(PrState.Open, pull.prState)
        assertEquals("abc", pull.head.sha)
        assertNull(pull.hasConflicts)
        assertFalse(pull.hasGitHubAutoMerge)
    }

    @Test fun readsConflictsAndAutoMerge() {
        on("GET", "/repos/me/app/pulls/7", 200, pullJson(""""state": "open", "mergeable": false, "mergeable_state": "dirty", "auto_merge": {"merge_method": "squash"}"""))
        val pull = github.pull(repo, 7)
        assertEquals(true, pull.hasConflicts)
        assertTrue(pull.hasGitHubAutoMerge)
    }

    @Test fun tellsMergedFromClosed() {
        on("GET", "/repos/me/app/pulls/7", 200, pullJson(""""state": "closed", "merged": true, "merged_at": "2026-01-02T03:04:05Z", "merge_commit_sha": "def""""))
        val merged = github.pull(repo, 7)
        assertEquals(PrState.Merged, merged.prState)
        assertEquals("def", merged.mergeCommitSha)
        responses.clear()
        on("GET", "/repos/me/app/pulls/7", 200, pullJson(""""state": "closed", "merged": false"""))
        assertEquals(PrState.Closed, github.pull(repo, 7).prState)
    }

    @Test fun findsTheTaskBranchsPullRequestInAnyState() {
        on("GET", "/repos/me/app/pulls?state=all&head=me:claude/fix", 200, "[${pullJson(""""state": "closed"""")}]")
        assertEquals(7, github.findPull(repo, "claude/fix")?.number)
        responses.clear()
        on("GET", "/repos/me/app/pulls?state=all&head=me:claude/fix", 200, "[]")
        assertNull(github.findPull(repo, "claude/fix"))
    }

    @Test fun opensAPullRequestAgainstTheDefaultBranch() {
        val dev = repo.copy(defaultBranch = "develop")
        on("POST", "/repos/me/app/pulls", 201, pullJson(""""state": "open""""))
        assertEquals(7, github.openPull(dev, "claude/fix", "Fix it", "body").number)
        val body = sent.single().body!!
        assertTrue("\"head\":\"claude/fix\"" in body)
        assertTrue("\"base\":\"develop\"" in body)
    }

    @Test fun aMissingBranchIsNotAnError() {
        on("GET", "/repos/me/app/branches/claude/fix", 404, """{"message": "Branch not found"}""")
        assertFalse(github.branchExists(repo, "claude/fix"))
        responses.clear()
        on("GET", "/repos/me/app/branches/claude/fix", 200, """{"name": "claude/fix"}""")
        assertTrue(github.branchExists(repo, "claude/fix"))
        responses.clear()
        sent.clear()
        on("GET", "/repos/me/app/branches/claude/fix", 500, "")
        assertEquals(500, assertFailsWith<GitHubException> { github.branchExists(repo, "claude/fix") }.code)
        assertEquals(3, sent.count { it.method == "GET" && it.path.endsWith("/branches/claude/fix") }, "transient 500s are retried")
    }

    @Test fun retriesABriefOutageThenSucceeds() {
        on("GET", "/repos/me/app/branches/claude/fix", 502, "")
        on("GET", "/repos/me/app/branches/claude/fix", 200, """{"name": "claude/fix"}""")
        assertTrue(github.branchExists(repo, "claude/fix"))
        assertEquals(2, sent.size)
    }

    @Test fun aPermanentErrorIsNotRetried() {
        on("GET", "/repos/me/app/branches/claude/fix", 401, """{"message": "Bad credentials"}""")
        assertEquals(401, assertFailsWith<GitHubException> { github.branchExists(repo, "claude/fix") }.code)
        assertEquals(1, sent.size)
    }

    @Test fun fetchesAnIssueAndRejectsPullRequestsFromTheSharedEndpoint() {
        on("GET", "/repos/me/app/issues/34", 200, """{"number":34,"title":"Fix login","updated_at":"2026-01-02T03:04:05Z","body":"Details"}""")
        val issue = github.issue(repo, 34)
        assertEquals(34, issue.number)
        assertEquals("Fix login", issue.title)
        assertEquals("Details", issue.body)

        on("GET", "/repos/me/app/issues/35", 200, """{"number":35,"title":"A pull request","updated_at":"2026-01-02T03:04:05Z","pull_request":{"url":"https://api.github.com/repos/me/app/pulls/35"}}""")
        assertFailsWith<IllegalArgumentException> { github.issue(repo, 35) }
        assertFailsWith<IllegalArgumentException> { github.issue(repo, 0) }
    }

    @Test fun listsOpenIssuesButFiltersPullRequests() {
        on(
            "GET", "/repos/me/app/issues?state=open&sort=created&direction=desc&per_page=5", 200,
            """[
                {"number":34,"title":"Fix login","updated_at":"2026-01-02T03:04:05Z"},
                {"number":35,"title":"Pull request","updated_at":"2026-01-02T03:04:05Z","pull_request":{"url":"https://api.github.com/repos/me/app/pulls/35"}}
            ]""",
        )
        assertEquals(listOf(34), github.openIssues(repo).map { it.number })
        assertFailsWith<IllegalArgumentException> { github.openIssues(repo, 0) }
    }

    @Test fun returnsNewReviewCommentsOldestFirst() {
        on(
            "GET", "/repos/me/app/pulls/7/comments?sort=created&direction=desc&per_page=100&page=1", 200,
            """[
                {"id":12,"body":"Second","path":"src/B.kt","user":{"login":"reviewer"}},
                {"id":11,"body":"First","path":"src/A.kt","user":{"login":"author"}}
            ]""",
        )
        val comments = github.reviewComments(repo, 7, afterId = 11)
        assertEquals(listOf(12L), comments.map { it.id })
        assertEquals("reviewer", comments.single().user?.login)
    }

    @Test fun pagesReviewCommentsUntilTheLastSeenId() {
        val firstPage = (201 downTo 102).joinToString(",") { """{"id":$it,"body":"Comment $it"}""" }
        on(
            "GET", "/repos/me/app/pulls/7/comments?sort=created&direction=desc&per_page=100&page=1", 200,
            "[$firstPage]",
        )
        on(
            "GET", "/repos/me/app/pulls/7/comments?sort=created&direction=desc&per_page=100&page=2", 200,
            """[{"id":101,"body":"New"},{"id":100,"body":"Seen"},{"id":50,"body":"Old"}]""",
        )

        val comments = github.reviewComments(repo, 7, afterId = 100).map { it.id }
        assertEquals((101L..201L).toList(), comments)
        assertEquals(listOf(1, 2), sent.map { Regex("[?&]page=(\\d+)").find(it.path)!!.groupValues[1].toInt() })
    }

    @Test fun aReplyWithNoBodyIsReportedByItsStatus() {
        val bare = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(502).message("Bad gateway").build()
        }.build()
        val e = assertFailsWith<GitHubException> { GitHub("token", bare, pause = {}).branchExists(repo, "claude/fix") }
        assertEquals(502, e.code)
        assertEquals("GitHub: HTTP 502", e.message)
    }

    // ---- Repos and commits ----------------------------------------------------------------------------------

    private fun repoJson(name: String, push: Boolean, branch: String = "main") =
        """{"name": "$name", "full_name": "me/$name", "default_branch": "$branch", "owner": {"login": "me"}, "permissions": {"push": $push, "pull": true}}"""

    @Test fun looksUpARepoWithItsDefaultBranch() {
        on("GET", "/repos/me/app", 200, repoJson("app", push = true, branch = "trunk"))
        assertEquals(RepoRef("me", "app", "trunk"), github.repo("me", "app"))
    }

    @Test fun refusesARepoTheTokenCannotPushTo() {
        on("GET", "/repos/me/app", 200, repoJson("app", push = false))
        val e = assertFailsWith<GitHubException> { github.repo("me", "app") }
        assertEquals(403, e.code)
        assertTrue("can't push" in e.message!!)
    }

    @Test fun listsOnlyReposTheTokenCanPushTo() {
        on("GET", "/user/repos?sort=pushed&per_page=100", 200, "[${repoJson("app", true)}, ${repoJson("fork", false)}]")
        assertEquals(listOf(RepoRef("me", "app")), github.myRepos())
    }

    @Test fun readsTheLatestCommitsFirstLine() {
        on(
            "GET", "/repos/me/app/commits/main", 200,
            """{"sha": "abc", "commit": {"message": "  Add dark mode  \n\nLonger description", "author": {"name": "Mel", "date": "2026-01-02T03:04:05Z"}}}""",
        )
        assertEquals(
            CommitInfo("abc", "Add dark mode", "Mel", java.time.Instant.parse("2026-01-02T03:04:05Z").toEpochMilli()),
            github.latestCommit(repo),
        )
    }

    @Test fun anEmptyRepoHasNoLatestCommit() {
        on("GET", "/repos/me/app/commits/main", 409, """{"message": "Git Repository is empty."}""")
        assertNull(github.latestCommit(repo))
    }

    @Test fun nextReleaseTagFollowsTheRepoTags() {
        on("GET", "/repos/me/app/tags?per_page=100", 200, """[{"name": "v1.2.3"}, {"name": "v1.10.0"}, {"name": "nightly"}]""")
        on("POST", "/repos/me/app/releases", 201, """{"tag_name": "v1.10.1"}""")
        val tag = github.createRelease(repo, Versions.nextPatch(github.tags(repo)), "def")
        assertEquals("v1.10.1", tag)
        val body = sent.last().body!!
        assertTrue("\"tag_name\":\"v1.10.1\"" in body)
        assertTrue("\"target_commitish\":\"def\"" in body)
    }

    // ---- Gists ----------------------------------------------------------------------------------------------

    @Test fun readsALargeGistFileFromItsRawUrl() {
        val raw = "https://gist.githubusercontent.com/me/g1/raw/sync.json"
        on("GET", "/gists/g1", 200, """{"id": "g1", "files": {"sync.json": {"content": "{\"cut", "truncated": true, "raw_url": "$raw"}}}""")
        on("GET", raw, 200, """{"complete": true}""")
        assertEquals("""{"complete": true}""", github.gistFile("g1", "sync.json"))
        assertNull(github.gistFile("g1", "other.json"))
    }

    @Test fun neverSendsTheTokenToAnotherHostForAGistFile() {
        on("GET", "/gists/g1", 200, """{"id": "g1", "files": {"sync.json": {"content": "{", "truncated": true, "raw_url": "https://example.com/steal"}}}""")
        assertNull(github.gistFile("g1", "sync.json"))
        assertEquals(listOf("/gists/g1"), sent.map { it.path })
    }

    // ---- Sync repo ------------------------------------------------------------------------------------------

    @Test fun readsAndWritesTheSyncFile() {
        val encoded = java.util.Base64.getEncoder().encodeToString("""{"a": 1}""".toByteArray())
        val sync = RepoRef("me", "cruise-control-sync")
        on("GET", "/repos/me/cruise-control-sync/contents/s.json", 200, """{"sha": "abc", "size": 8, "encoding": "base64", "content": "${encoded.chunked(4).joinToString("\\n")}"}""")
        on("PUT", "/repos/me/cruise-control-sync/contents/s.json", 200, "{}")
        val file = github.repoFile(sync, "s.json")!!
        assertEquals("""{"a": 1}""", file.content)
        assertEquals("abc", file.sha)
        github.putRepoFile(sync, "s.json", "hi", sha = "abc", message = "Sync")
        val body = sent.last().body!!
        assertTrue("\"sha\":\"abc\"" in body)
        assertTrue("\"content\":\"aGk=\"" in body)
    }

    @Test fun aMissingSyncFileOrRepoIsNull() {
        on("GET", "/repos/me/cruise-control-sync/contents/s.json", 404, """{"message": "Not Found"}""")
        on("GET", "/repos/me/cruise-control-sync", 404, """{"message": "Not Found"}""")
        assertNull(github.repoFile(RepoRef("me", "cruise-control-sync"), "s.json"))
        assertNull(github.repoVisibility("me", "cruise-control-sync"))
    }

    @Test fun makesTheSyncRepoPrivate() {
        on("POST", "/user/repos", 201, """{"name": "cruise-control-sync", "default_branch": "main", "owner": {"login": "me"}, "private": true}""")
        assertEquals("me/cruise-control-sync", github.createPrivateRepo("cruise-control-sync", "d").fullName)
        val body = sent.last().body!!
        assertTrue("\"private\":true" in body)
        assertTrue("\"auto_init\":true" in body)
    }

    // ---- Close / reopen and Actions logs --------------------------------------------------------------------

    @Test fun closesAndReopensAPullRequest() {
        on("PATCH", "/repos/me/app/pulls/7", 200, pullJson(""""state": "closed", "merged": false"""))
        assertEquals(PrState.Closed, github.setPullState(repo, 7, open = false).prState)
        assertEquals("""{"state":"closed"}""", sent.last().body)
        responses.clear()
        sent.clear()
        on("PATCH", "/repos/me/app/pulls/7", 200, pullJson(""""state": "open", "merged": false"""))
        assertEquals(PrState.Open, github.setPullState(repo, 7, open = true).prState)
        assertEquals("""{"state":"open"}""", sent.last().body)
    }

    @Test fun failingJobLogsReturnsTheLastLinesOfEachFailedJob() {
        on(
            "GET", "/repos/me/app/actions/runs?head_sha=abc&per_page=20", 200,
            """{"workflow_runs":[{"id":11,"name":"CI","status":"completed","conclusion":"failure"},{"id":12,"name":"ok","status":"completed","conclusion":"success"}]}""",
        )
        on(
            "GET", "/repos/me/app/actions/runs/11/jobs", 200,
            """{"jobs":[{"id":21,"name":"build","status":"completed","conclusion":"failure"},{"id":22,"name":"lint","status":"completed","conclusion":"success"}]}""",
        )
        on("GET", "/repos/me/app/actions/jobs/21/logs", 200, "line1\nline2\nAssertion failed")
        val logs = github.failingJobLogs(repo, "abc")
        assertEquals(listOf(FailingJobLog("build", "line1\nline2\nAssertion failed")), logs)
    }

    @Test fun failingJobLogsAreEmptyWhenActionsCannotBeRead() {
        on("GET", "/repos/me/app/actions/runs?head_sha=abc&per_page=20", 403, """{"message": "Resource not accessible by personal access token"}""")
        assertTrue(github.failingJobLogs(repo, "abc").isEmpty())
    }

    @Test fun tailLinesKeepsTheEndAndCapsLength() {
        assertEquals("b\nc", GitHub.tailLines("a\nb\nc", maxLines = 2))
        assertEquals("…\n2345", GitHub.tailLines("12345", maxLines = 10, maxChars = 4))
    }
}
