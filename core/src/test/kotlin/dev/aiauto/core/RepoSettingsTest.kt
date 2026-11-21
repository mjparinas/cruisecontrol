package dev.aiauto.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepoSettingsTest {
    private val app = RepoRef("me", "app")
    private val site = RepoRef("me", "site")

    @Test fun aRepoWithNoSettingsGetsTheDefaults() {
        val settings = RepoSettings.of(emptyMap(), app)
        assertEquals(RepoSettings(autoMerge = true, autoFixChecks = true, autoFixConflicts = true, autoReleaseSince = null), settings)
        assertEquals("", settings.instructions)
    }

    @Test fun findsARepoBySavedNameInAnyCase() {
        val saved = mapOf("Me/App" to RepoSettings(autoMerge = false))
        assertEquals(false, RepoSettings.of(saved, app).autoMerge)
        assertEquals(true, RepoSettings.of(saved, site).autoMerge)
    }

    @Test fun seedingGivesOnlyReposWithoutSettingsTheOldGlobalSwitches() {
        val legacy = RepoSettings(autoMerge = false, autoFixChecks = false, autoReleaseSince = 5)
        val kept = RepoSettings(instructions = "Use pnpm")
        val seeded = RepoSettings.seed(mapOf("me/app" to kept), listOf(app, site), legacy)
        assertEquals(kept, seeded["me/app"])
        assertEquals(legacy, seeded["me/site"])
    }

    @Test fun autoReleaseKeepsWhenItWasFirstTurnedOn() {
        val on = RepoSettings().withAutoRelease(true, now = 100)
        assertEquals(100L, on.autoReleaseSince)
        assertEquals(100L, on.withAutoRelease(true, now = 200).autoReleaseSince)
        assertNull(on.withAutoRelease(false, now = 300).autoReleaseSince)
    }

    @Test fun instructionLinesSkipBlanksAndTrim() {
        assertEquals(listOf("Use pnpm", "No new files in lib"), RepoSettings(instructions = "  Use pnpm \n\n No new files in lib").instructionLines)
    }

    @Test fun summarySaysWhatTheAppDoesOnItsOwn() {
        assertEquals("Merges when checks pass, fixes conflicts and failing checks.", RepoSettings().summary())
        assertEquals(
            "Waits for you to merge, fixes failing checks, releases on merge, 1 rule for Claude.",
            RepoSettings(autoMerge = false, autoFixConflicts = false, autoReleaseSince = 1, instructions = "Use pnpm").summary(),
        )
        assertEquals("Waits for you to merge, 2 rules for Claude.", RepoSettings.MANUAL.copy(instructions = "a\nb").summary())
    }

    @Test fun readsSavedSettingsWithMissingFieldsAsDefaults() {
        val json = Json { ignoreUnknownKeys = true }
        val saved = json.decodeFromString<Map<String, RepoSettings>>("""{"me/app": {"autoMerge": false}}""")
        assertEquals(RepoSettings(autoMerge = false), saved["me/app"])
    }
}
