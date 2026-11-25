package dev.aiauto.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Keeps AGENTS.md in step with the repository: the versions and numbers it lists must match the build files and
 * the code, and every repo path it names must exist. Fix AGENTS.md (or the code) when this fails.
 */
class AgentsDocTest {
    // Gradle runs tests from the core module's directory.
    private val root = File("..").canonicalFile
    private val doc = read("AGENTS.md")

    private fun read(path: String): String {
        val file = File(root, path)
        assertTrue(file.isFile, "$path is missing")
        return file.readText()
    }

    private fun find(text: String, pattern: String, what: String): String =
        Regex(pattern).find(text)?.groupValues?.get(1) ?: fail("Couldn't find $what")

    /** The "- Name: value" lines under [heading]. */
    private fun entries(heading: String): Map<String, String> {
        val section = doc.substringAfter("## $heading\n", "").substringBefore("\n## ")
        assertTrue(section.isNotEmpty(), "AGENTS.md has no '$heading' section")
        return Regex("""^- (.+?): (.+)$""", RegexOption.MULTILINE).findAll(section)
            .associate { it.groupValues[1].trim() to it.groupValues[2].trim() }
    }

    private fun assertMatches(heading: String, expected: Map<String, String>) {
        val listed = entries(heading)
        assertEquals(expected.keys, listed.keys, "AGENTS.md '$heading' should list exactly these, each checked by this test")
        expected.forEach { (name, value) -> assertEquals(value, listed[name], "AGENTS.md '$heading': $name") }
    }

    @Test fun hasTheSectionsAgentsRelyOn() {
        listOf(
            "Keep this file in sync", "Product decisions", "Engineering decisions", "Development process",
            "Pinned versions", "Numbers the code depends on", "Decision log",
        ).forEach { assertTrue("\n## $it\n" in doc, "AGENTS.md is missing the '$it' section") }
    }

    @Test fun claudeMdImportsAgentsMd() {
        assertTrue(read(ClaudeAgents.NOTES_FILE).lines().any { it.trim() == "@AGENTS.md" }, "CLAUDE.md should import AGENTS.md")
    }

    @Test fun pinnedVersionsMatchTheBuild() {
        val rootBuild = read("build.gradle.kts")
        val coreBuild = read("core/build.gradle.kts")
        val appBuild = read("app/build.gradle.kts")
        val builds = rootBuild + coreBuild + appBuild
        fun dependency(module: String) = find(builds, """"${Regex.escape(module)}:([^"]+)"""", module)

        val kotlinVersions = Regex("""kotlin\("[\w.]+"\) version "([^"]+)"""").findAll(rootBuild).map { it.groupValues[1] }.toSet()
        assertEquals(1, kotlinVersions.size, "All Kotlin plugins should share one version")
        val jdk = find(coreBuild, """JavaVersion\.VERSION_(\d+)""", "the core JDK")
        assertEquals(jdk, find(appBuild, """JavaVersion\.VERSION_(\d+)""", "the app JDK"), "core and app should target the same JDK")
        assertEquals(jdk, find(read(".github/workflows/build.yml"), """java-version: (\d+)""", "the CI JDK"), "CI should build with the JDK the code targets")

        assertMatches(
            "Pinned versions",
            mapOf(
                "Gradle wrapper" to find(read("gradle/wrapper/gradle-wrapper.properties"), """gradle-([\d.]+)-bin\.zip""", "the Gradle wrapper"),
                "Android Gradle Plugin" to find(rootBuild, """id\("com\.android\.application"\) version "([^"]+)"""", "the Android Gradle Plugin"),
                "Kotlin" to kotlinVersions.single(),
                "JDK" to jdk,
                "compileSdk" to find(appBuild, """release\((\d+)\)""", "compileSdk") + "." + find(appBuild, """minorApiLevel = (\d+)""", "the compileSdk minor level"),
                "targetSdk" to find(appBuild, """targetSdk = (\d+)""", "targetSdk"),
                "minSdk" to find(appBuild, """minSdk = (\d+)""", "minSdk"),
                "anthropic-java" to dependency("com.anthropic:anthropic-java"),
                "OkHttp" to dependency("com.squareup.okhttp3:okhttp"),
                "kotlinx-serialization-json" to dependency("org.jetbrains.kotlinx:kotlinx-serialization-json"),
                "kotlinx-coroutines" to dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core"),
                "Compose BOM" to dependency("androidx.compose:compose-bom"),
                "Car App Library" to dependency("androidx.car.app:app"),
            ),
        )
    }

    @Test fun numbersMatchTheCode() {
        val dailyLimit = find(read("app/src/main/java/dev/aiauto/data/Stores.kt"), """DEFAULT_DAILY_LIMIT = ([\d.]+)""", "the default daily limit")
        val issueBodyLimit = find(read("core/src/main/kotlin/dev/aiauto/core/TaskEngine.kt"), """MAX_ISSUE_BODY_CHARS = ([\d_]+)""", "the issue body limit")
            .replace("_", "")
        assertMatches(
            "Numbers the code depends on",
            mapOf(
                "Default coding model" to AgentProfile().modelChoice.label,
                "Default coding effort" to AgentProfile().effortChoice.label,
                "Default car chat effort" to ChatProfile().effort.label,
                "Daily spending limit default (USD)" to dailyLimit.toDouble().toInt().toString(),
                "Pull request watch window (hours)" to (PullRequests.WATCH_MILLIS / 3_600_000).toString(),
                "Automatic fixes per task" to PullRequests.MAX_AUTO_FIXES.toString(),
                "Stuck task alert (minutes)" to (TaskEngine.STUCK_MILLIS / 60_000).toString(),
                "Car chat exchanges remembered" to ChatMemory.KEEP.toString(),
                "Synced messages per task" to SyncMerge.MESSAGES_PER_TASK.toString(),
                "Car history rows" to RepoHistory.CAR_ROWS.toString(),
                "Cleared task histories kept locally" to RepoHistory.CLEARED_TASKS_KEEP.toString(),
                "Issue body included in a task (characters)" to issueBodyLimit,
                "Sync repo" to RepoSync.REPO,
                "Update source" to UpdateSource.APP_REPO,
                "Repository notes file" to ClaudeAgents.NOTES_FILE,
            ),
        )
    }

    @Test fun everyPathItNamesExists() {
        val paths = Regex("`([^`\\s]+)`").findAll(doc).map { it.groupValues[1] }
            .filter { Regex("""^(core|app|docs|gradle|\.github)/.+|^[\w-]+\.(md|kts)$""").matches(it) }
            .toList()
        assertTrue(paths.isNotEmpty())
        val missing = paths.filterNot { File(root, it).exists() }
        assertTrue(missing.isEmpty(), "AGENTS.md names paths that don't exist: $missing")
    }
}
