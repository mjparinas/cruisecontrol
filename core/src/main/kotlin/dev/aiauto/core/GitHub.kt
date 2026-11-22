package dev.aiauto.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class GitHubException(val code: Int, message: String) : IOException(message)

@Serializable
data class PullRequest(
    val number: Int,
    @SerialName("html_url") val htmlUrl: String,
    val state: String = "open",
    val title: String = "",
    val merged: Boolean = false,
    val draft: Boolean = false,
    @SerialName("changed_files") val changedFiles: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
    /** Null while GitHub is still working it out, and always null in list results. */
    val mergeable: Boolean? = null,
    /** "dirty" means there are merge conflicts. */
    @SerialName("mergeable_state") val mergeableState: String? = null,
    @SerialName("node_id") val nodeId: String = "",
    val head: Head = Head(),
    @SerialName("auto_merge") val autoMerge: kotlinx.serialization.json.JsonElement? = null,
    @SerialName("merged_at") val mergedAt: String? = null,
    @SerialName("merge_commit_sha") val mergeCommitSha: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    @Serializable
    data class Head(val sha: String = "", val ref: String = "")

    val prState: PrState get() = when {
        merged -> PrState.Merged
        state == "closed" -> PrState.Closed
        else -> PrState.Open
    }

    /** True when GitHub has worked out that it conflicts with the base branch, null while it's still checking. */
    val hasConflicts: Boolean? get() = when {
        mergeableState == "dirty" -> true
        mergeable == null -> null
        else -> mergeable == false
    }

    val hasGitHubAutoMerge: Boolean get() = autoMerge != null && autoMerge !is kotlinx.serialization.json.JsonNull
}

/** The newest commit on a branch. */
data class CommitInfo(val sha: String, val message: String, val author: String, val timeMillis: Long?)

/** One open pull request with its checks, for the project view. */
data class PullStatus(val pull: PullRequest, val checks: ChecksSummary)

/** One changed file from a pull request's patch response. */
@Serializable
data class PullFile(
    val filename: String,
    val status: String = "modified",
    val additions: Int = 0,
    val deletions: Int = 0,
    val patch: String? = null,
)

@Serializable
data class GitHubIssue(
    val number: Int,
    val title: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("pull_request") val pullRequest: kotlinx.serialization.json.JsonElement? = null,
)

/** A repo at a glance: what landed last, whether its main branch is green, and what's waiting for review. */
data class RepoStatus(
    val repo: RepoRef,
    val latest: CommitInfo?,
    val branchChecks: ChecksSummary,
    val pulls: List<PullStatus>,
)

/** How a merge attempt went. */
sealed interface MergeResult {
    data object Merged : MergeResult
    /** GitHub's auto-merge is on; GitHub merges once the required checks pass. */
    data object AutoMergeOn : MergeResult
    data class Refused(val reason: String) : MergeResult
}

/** The few GitHub REST calls the app needs, authenticated with the user's token. Open so tests can fake it. */
open class GitHub(private val token: String, private val http: OkHttpClient = OkHttpClient()) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Serializable
    private data class CheckRuns(@SerialName("check_runs") val checkRuns: List<CheckRun> = emptyList()) {
        @Serializable data class CheckRun(val name: String = "", val status: String = "", val conclusion: String? = null)
    }

    @Serializable
    private data class CombinedStatus(
        val state: String = "",
        @SerialName("total_count") val totalCount: Int = 0,
        val statuses: List<Item> = emptyList(),
    ) {
        @Serializable data class Item(val context: String = "", val state: String = "")
    }

    @Serializable
    private data class CommitJson(val sha: String, val commit: Inner) {
        @Serializable data class Inner(val message: String = "", val author: Author? = null)
        @Serializable data class Author(val name: String = "", val date: String? = null)
    }

    @Serializable
    private data class MergeBody(@SerialName("merge_method") val mergeMethod: String)

    @Serializable
    private data class RepoJson(
        val name: String,
        @SerialName("full_name") val fullName: String,
        @SerialName("default_branch") val defaultBranch: String,
        val owner: Owner,
        val permissions: Permissions? = null,
    ) {
        @Serializable data class Owner(val login: String)
        @Serializable data class Permissions(val push: Boolean = false)

        fun toRef() = RepoRef(owner.login, name, defaultBranch)
    }

    @Serializable
    private data class NewPull(val title: String, val head: String, val base: String, val body: String)

    /** Looks up a repo, confirming the token can see it and getting its default branch. */
    fun repo(owner: String, name: String): RepoRef {
        val r = json.decodeFromString<RepoJson>(get("/repos/$owner/$name"))
        if (r.permissions?.push == false) throw GitHubException(403, "Your token can read ${r.fullName} but can't push to it.")
        return r.toRef()
    }

    /** Repos the token can push to, most recently pushed first. */
    fun myRepos(): List<RepoRef> =
        json.decodeFromString<List<RepoJson>>(get("/user/repos?sort=pushed&per_page=100"))
            .filter { it.permissions?.push != false }
            .map { it.toRef() }

    open fun branchExists(repo: RepoRef, branch: String): Boolean = try {
        get("/repos/${repo.fullName}/branches/$branch")
        true
    } catch (e: GitHubException) {
        if (e.code == 404) false else throw e
    }

    open fun findPull(repo: RepoRef, branch: String): PullRequest? =
        json.decodeFromString<List<PullRequest>>(get("/repos/${repo.fullName}/pulls?state=all&head=${repo.owner}:$branch"))
            .firstOrNull()

    open fun openPull(repo: RepoRef, branch: String, title: String, body: String): PullRequest {
        val payload = json.encodeToString(NewPull.serializer(), NewPull(title, branch, repo.defaultBranch, body))
        return json.decodeFromString(send("POST", "/repos/${repo.fullName}/pulls", payload))
    }

    @Serializable
    private data class TagJson(val name: String)

    @Serializable
    private data class NewRelease(
        @SerialName("tag_name") val tagName: String,
        @SerialName("target_commitish") val target: String,
        val name: String,
        @SerialName("generate_release_notes") val generateNotes: Boolean = true,
    )

    @Serializable
    private data class ReleaseJson(@SerialName("tag_name") val tagName: String)

    open fun tags(repo: RepoRef): List<String> =
        json.decodeFromString<List<TagJson>>(get("/repos/${repo.fullName}/tags?per_page=100")).map { it.name }

    /** Publishes a release, creating [tag] at [target] with GitHub's generated notes. Returns the tag. */
    open fun createRelease(repo: RepoRef, tag: String, target: String): String {
        val payload = json.encodeToString(NewRelease.serializer(), NewRelease(tag, target, tag))
        return json.decodeFromString<ReleaseJson>(send("POST", "/repos/${repo.fullName}/releases", payload)).tagName
    }

    open fun pull(repo: RepoRef, number: Int): PullRequest =
        json.decodeFromString(get("/repos/${repo.fullName}/pulls/$number"))

    /** Files and unified diffs for one pull request, capped at GitHub's 100-file page size. */
    open fun pullFiles(repo: RepoRef, number: Int): List<PullFile> =
        json.decodeFromString(get("/repos/${repo.fullName}/pulls/$number/files?per_page=100"))

    /** Submits an approving review on GitHub. */
    open fun approvePull(repo: RepoRef, number: Int) {
        send("POST", "/repos/${repo.fullName}/pulls/$number/reviews", "{\"event\":\"APPROVE\"}")
    }

    open fun openPulls(repo: RepoRef, limit: Int = 10): List<PullRequest> =
        json.decodeFromString(get("/repos/${repo.fullName}/pulls?state=open&sort=updated&direction=desc&per_page=$limit"))

    /** Issues updated since the previous drive. GitHub includes pull requests in this endpoint too. */
    open fun issuesUpdatedSince(repo: RepoRef, sinceMillis: Long): List<GitHubIssue> {
        val since = java.time.Instant.ofEpochMilli(sinceMillis).toString()
        return json.decodeFromString(get("/repos/${repo.fullName}/issues?state=all&since=$since&per_page=100"))
    }

    /** The newest commit on [branch], or null when the branch is empty. */
    open fun latestCommit(repo: RepoRef, branch: String = repo.defaultBranch): CommitInfo? = try {
        val c = json.decodeFromString<CommitJson>(get("/repos/${repo.fullName}/commits/$branch"))
        CommitInfo(
            sha = c.sha,
            message = c.commit.message.lineSequence().firstOrNull().orEmpty().trim(),
            author = c.commit.author?.name.orEmpty(),
            timeMillis = c.commit.author?.date?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() },
        )
    } catch (e: GitHubException) {
        if (e.code == 404 || e.code == 409) null else throw e
    }

    /**
     * Combines check runs (GitHub Actions and apps) with commit statuses for [sha].
     * A token without permission to read one of them just leaves that source out; if it can read neither, the result is [Checks.Unknown].
     */
    open fun checks(repo: RepoRef, sha: String): ChecksSummary {
        var readable = 0
        val pending = mutableListOf<String>()
        val failing = mutableListOf<String>()
        var passing = 0
        try {
            val runs = json.decodeFromString<CheckRuns>(get("/repos/${repo.fullName}/commits/$sha/check-runs?per_page=100")).checkRuns
            readable++
            runs.forEach { run ->
                when {
                    run.status != "completed" -> pending += run.name
                    run.conclusion in OK_CONCLUSIONS -> passing++
                    else -> failing += run.name
                }
            }
        } catch (e: GitHubException) {
            if (e.code != 403 && e.code != 404) throw e
        }
        try {
            val combined = json.decodeFromString<CombinedStatus>(get("/repos/${repo.fullName}/commits/$sha/status"))
            readable++
            combined.statuses.forEach { st ->
                when (st.state) {
                    "success" -> passing++
                    "pending" -> pending += st.context
                    else -> failing += st.context
                }
            }
        } catch (e: GitHubException) {
            if (e.code != 403 && e.code != 404) throw e
        }
        val total = passing + pending.size + failing.size
        val state = when {
            readable == 0 -> Checks.Unknown
            failing.isNotEmpty() -> Checks.Failing
            pending.isNotEmpty() -> Checks.Pending
            total > 0 -> Checks.Passing
            else -> Checks.None
        }
        return ChecksSummary(state, failing.distinct(), total)
    }

    /** Everything the project view shows for [repo]. */
    open fun status(repo: RepoRef, maxPulls: Int = 6): RepoStatus {
        val latest = latestCommit(repo)
        val branchChecks = latest?.let { checks(repo, it.sha) } ?: ChecksSummary(Checks.None)
        val pulls = openPulls(repo, maxPulls).map { PullStatus(it, checks(repo, it.head.sha)) }
        return RepoStatus(repo, latest, branchChecks, pulls)
    }

    /** Merges pull request [number], trying squash first, then a merge commit, then rebase, whichever the repo allows. */
    open fun merge(repo: RepoRef, number: Int): MergeResult {
        var lastRefusal = "GitHub wouldn't merge it."
        for (method in listOf("squash", "merge", "rebase")) {
            try {
                send("PUT", "/repos/${repo.fullName}/pulls/$number/merge", json.encodeToString(MergeBody.serializer(), MergeBody(method)))
                return MergeResult.Merged
            } catch (e: GitHubException) {
                val message = e.message.orEmpty().removePrefix("GitHub: ")
                when {
                    // This merge method is turned off for the repo; try the next one.
                    e.code == 405 && "not allowed" in message.lowercase() -> lastRefusal = message
                    e.code == 405 || e.code == 409 || e.code == 422 -> return MergeResult.Refused(message.ifBlank { "GitHub can't merge it right now." })
                    else -> throw e
                }
            }
        }
        return MergeResult.Refused(lastRefusal)
    }

    /**
     * Turns on GitHub's own auto-merge, so GitHub merges once required checks pass, even with the phone off.
     * Returns false when the repo doesn't allow it or the pull request has no required checks to wait for.
     */
    open fun enableAutoMerge(pull: PullRequest): Boolean {
        if (pull.nodeId.isEmpty()) return false
        val query = "mutation(\$id: ID!) { enablePullRequestAutoMerge(input: {pullRequestId: \$id, mergeMethod: SQUASH}) { clientMutationId } }"
        val body = kotlinx.serialization.json.buildJsonObject {
            put("query", kotlinx.serialization.json.JsonPrimitive(query))
            put("variables", kotlinx.serialization.json.buildJsonObject { put("id", kotlinx.serialization.json.JsonPrimitive(pull.nodeId)) })
        }.toString()
        val response = try {
            send("POST", "/graphql", body)
        } catch (e: GitHubException) {
            if (e.code == 401 || e.code >= 500) throw e
            return false
        }
        return !response.contains("\"errors\"")
    }

    // ---- Gists, for syncing between devices ----------------------------------------------------------------

    @Serializable
    data class GistFile(val content: String? = null, val truncated: Boolean = false, @SerialName("raw_url") val rawUrl: String? = null)

    @Serializable
    data class Gist(val id: String, val description: String? = null, val files: Map<String, GistFile> = emptyMap())

    /** The token owner's gists, newest first. Their file contents aren't included. */
    open fun myGists(perPage: Int = 100): List<Gist> = json.decodeFromString(get("/gists?per_page=$perPage"))

    /** One gist with its files' contents. Large files come back truncated, so those are read from their raw URL. */
    open fun gistFile(id: String, name: String): String? {
        val gist = json.decodeFromString<Gist>(get("/gists/$id"))
        val file = gist.files[name] ?: return null
        if (!file.truncated) return file.content
        // The token goes only to GitHub's own gist host, never to wherever a raw URL happens to point.
        val url = file.rawUrl?.toHttpUrlOrNull()?.takeIf { it.isHttps && it.host == GIST_RAW_HOST } ?: return null
        return http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { it.body.string() }
    }

    /** Makes a secret gist holding one file. Returns its id. */
    open fun createGist(description: String, name: String, content: String): String {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("description", kotlinx.serialization.json.JsonPrimitive(description))
            put("public", kotlinx.serialization.json.JsonPrimitive(false))
            put("files", gistFiles(name, content))
        }.toString()
        return json.decodeFromString<Gist>(send("POST", "/gists", body)).id
    }

    open fun updateGist(id: String, name: String, content: String) {
        val body = kotlinx.serialization.json.buildJsonObject { put("files", gistFiles(name, content)) }.toString()
        send("PATCH", "/gists/$id", body)
    }

    open fun deleteGist(id: String) {
        send("DELETE", "/gists/$id", null)
    }

    // ---- The private sync repo -------------------------------------------------------------------------------

    @Serializable
    private data class UserJson(val login: String)

    @Serializable
    private data class VisibilityJson(
        val name: String,
        @SerialName("default_branch") val defaultBranch: String = "main",
        val owner: RepoJson.Owner,
        val private: Boolean,
    )

    @Serializable
    private data class ContentJson(val sha: String, val content: String = "", val size: Long = 0, val encoding: String = "")

    /** A file in a repo, with the sha GitHub needs to replace it. */
    data class RepoFile(val content: String, val sha: String)

    /** The token owner's login. */
    open fun myLogin(): String = json.decodeFromString<UserJson>(get("/user")).login

    /** The repo and whether it is private, or null when it doesn't exist or the token can't see it. */
    open fun repoVisibility(owner: String, name: String): Pair<RepoRef, Boolean>? = try {
        val r = json.decodeFromString<VisibilityJson>(get("/repos/$owner/$name"))
        RepoRef(r.owner.login, r.name, r.defaultBranch) to r.private
    } catch (e: GitHubException) {
        if (e.code == 404) null else throw e
    }

    /** Makes a private repo on the token owner's account, with a first commit so files can be added. */
    open fun createPrivateRepo(name: String, description: String): RepoRef {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("name", kotlinx.serialization.json.JsonPrimitive(name))
            put("description", kotlinx.serialization.json.JsonPrimitive(description))
            put("private", kotlinx.serialization.json.JsonPrimitive(true))
            put("auto_init", kotlinx.serialization.json.JsonPrimitive(true))
        }.toString()
        val r = json.decodeFromString<VisibilityJson>(send("POST", "/user/repos", body))
        if (!r.private) throw SyncRepoNotPrivate("${r.owner.login}/${r.name}")
        return RepoRef(r.owner.login, r.name, r.defaultBranch)
    }

    /** One file on the default branch, or null when it isn't there yet. */
    open fun repoFile(repo: RepoRef, path: String): RepoFile? {
        val meta = try {
            json.decodeFromString<ContentJson>(get("/repos/${repo.fullName}/contents/$path"))
        } catch (e: GitHubException) {
            if (e.code == 404) return null
            throw e
        }
        // Files over 1 MB come back without their content, so read those raw.
        val text = if (meta.encoding == "base64" && (meta.content.isNotEmpty() || meta.size == 0L)) {
            String(java.util.Base64.getMimeDecoder().decode(meta.content), Charsets.UTF_8)
        } else {
            send("GET", "/repos/${repo.fullName}/contents/$path", null, accept = "application/vnd.github.raw+json")
        }
        return RepoFile(text, meta.sha)
    }

    /** Writes [path] in one commit. [sha] is the file's current sha, or null to create it. */
    open fun putRepoFile(repo: RepoRef, path: String, content: String, sha: String?, message: String) {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("message", kotlinx.serialization.json.JsonPrimitive(message))
            put("content", kotlinx.serialization.json.JsonPrimitive(java.util.Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))))
            if (sha != null) put("sha", kotlinx.serialization.json.JsonPrimitive(sha))
        }.toString()
        send("PUT", "/repos/${repo.fullName}/contents/$path", body)
    }

    private fun gistFiles(name: String, content: String) = kotlinx.serialization.json.buildJsonObject {
        put(name, kotlinx.serialization.json.buildJsonObject { put("content", kotlinx.serialization.json.JsonPrimitive(content)) })
    }

    private fun get(path: String) = send("GET", path, null)

    private fun send(method: String, path: String, body: String?, accept: String = "application/vnd.github+json"): String {
        val request = Request.Builder()
            .url("https://api.github.com$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .method(method, body?.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                val message = runCatching { json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(text)["message"]?.toString()?.trim('"') }
                    .getOrNull() ?: "HTTP ${response.code}"
                throw GitHubException(response.code, "GitHub: $message")
            }
            return text
        }
    }

    private companion object {
        val OK_CONCLUSIONS = setOf("success", "neutral", "skipped")
        const val GIST_RAW_HOST = "gist.githubusercontent.com"
    }
}
