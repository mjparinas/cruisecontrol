package dev.aiauto.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** A newer build of the app published as a GitHub release. */
data class AvailableUpdate(
    /** The release tag, like v0.3.0. */
    val tag: String,
    val apkUrl: String,
    val apkSize: Long,
    val pageUrl: String,
) {
    val version: String get() = tag.removePrefix("v")
}

/** Reads the app's own GitHub releases. The repo is public, so no token is needed. */
class UpdateSource(
    private val repo: String = APP_REPO,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ReleaseJson(
        @SerialName("tag_name") val tag: String,
        @SerialName("html_url") val pageUrl: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    ) {
        @Serializable
        data class Asset(
            val name: String,
            val size: Long = 0,
            @SerialName("browser_download_url") val url: String,
        )
    }

    /** The latest published release with an APK attached, if it's newer than [currentVersion]. */
    fun newerThan(currentVersion: String): AvailableUpdate? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        val body = http.newCall(request).execute().use { response ->
            if (response.code == 404) return null // no releases yet
            if (!response.isSuccessful) throw GitHubException(response.code, "GitHub: HTTP ${response.code}")
            response.body.string()
        }
        return parse(body, currentVersion)
    }

    internal fun parse(body: String, currentVersion: String): AvailableUpdate? {
        val release = json.decodeFromString<ReleaseJson>(body)
        if (release.draft || release.prerelease) return null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") && isGitHubDownload(it.url) } ?: return null
        if (!Versions.isNewer(release.tag, currentVersion)) return null
        return AvailableUpdate(release.tag, apk.url, apk.size, release.pageUrl)
    }

    /** Only downloads served by GitHub itself over HTTPS. */
    private fun isGitHubDownload(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && (parsed.host == "github.com" || parsed.host.endsWith(".githubusercontent.com"))
    }

    companion object {
        const val APP_REPO = "mjparinas/cruisecontrol"
    }
}

/** Compares dotted version numbers like "v0.10.1" and "0.9". Anything after a dash is ignored. */
object Versions {
    fun parse(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    /** The next patch version after the highest x.y.z tag, keeping its v prefix. v0.1.0 when there are none. */
    fun nextPatch(tags: List<String>): String {
        val semver = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")
        val latest = tags.mapNotNull { tag -> semver.matchEntire(tag)?.let { tag to it.groupValues.drop(1).map(String::toInt) } }
            .maxWithOrNull(compareBy({ it.second[0] }, { it.second[1] }, { it.second[2] }))
            ?: return "v0.1.0"
        val (major, minor, patch) = latest.second
        val prefix = if (latest.first.startsWith("v")) "v" else ""
        return "$prefix$major.$minor.${patch + 1}"
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
