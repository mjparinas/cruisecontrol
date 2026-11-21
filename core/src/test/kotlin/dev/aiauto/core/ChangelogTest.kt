package dev.aiauto.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps CHANGELOG.md ready to release from: every entry is a version, newest first, with only an Unreleased entry
 * allowed above them, and the README's release example names the newest version.
 */
class ChangelogTest {
    // Gradle runs tests from the core module's directory.
    private val root = File("..").canonicalFile
    private val headings = Regex("""^## (.+)$""", RegexOption.MULTILINE)
        .findAll(File(root, "CHANGELOG.md").readText()).map { it.groupValues[1].trim() }.toList()
    private val versions = headings.dropWhile { it == "Unreleased" }

    @Test fun entriesAreVersionsNewestFirst() {
        assertTrue(versions.isNotEmpty(), "CHANGELOG.md has no version entries")
        versions.forEach { assertTrue(Regex("""v\d+\.\d+\.\d+""").matches(it), "CHANGELOG.md heading '$it' should be a version like v1.2.3") }
        versions.zipWithNext().forEach { (newer, older) ->
            assertTrue(Versions.isNewer(newer, older), "CHANGELOG.md lists $newer above $older; newest goes first")
        }
    }

    @Test fun readmeReleaseExampleIsTheNewestVersion() {
        val readme = File(root, "README.md").readText()
        val example = Regex("""enter the next version, like `(v[\d.]+)`""").find(readme)?.groupValues?.get(1)
        assertEquals(versions.first(), example, "README's Making a release example should name the newest changelog version")
    }
}
