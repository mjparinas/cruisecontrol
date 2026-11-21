package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSearchTest {
    @Test fun blankQueryShowsEverything() {
        val search = SettingsSearch("   ")
        assertFalse(search.active)
        assertFalse(search.nothingFound)
        SettingsSearch.CATALOGUE.forEach { (section, settings) ->
            assertTrue(search.showsSection(section))
            settings.forEach { assertTrue(search.showsRow(section, it.title)) }
        }
    }

    @Test fun matchesTitleIgnoringCase() {
        val search = SettingsSearch("THEME")
        assertTrue(search.showsSection("Appearance"))
        assertTrue(search.showsRow("Appearance", "Theme"))
        assertFalse(search.showsRow("Appearance", "Accent color"))
        assertFalse(search.showsSection("Spending"))
    }

    @Test fun matchesKeywords() {
        val search = SettingsSearch("colour")
        assertTrue(search.showsRow("Appearance", "Accent color"))
        assertFalse(search.showsRow("Appearance", "Theme"))
        assertTrue(SettingsSearch("money").showsRow("Spending", "Daily limit"))
    }

    @Test fun sectionNameShowsAllItsRows() {
        val search = SettingsSearch("pull requests")
        SettingsSearch.CATALOGUE.getValue("Pull requests").forEach { assertTrue(search.showsRow("Pull requests", it.title)) }
        assertFalse(search.showsSection("Accounts"))
    }

    @Test fun everyWordMustMatch() {
        val search = SettingsSearch("car effort")
        assertTrue(search.showsRow("Claude", "Effort for the car chat"))
        assertFalse(search.showsRow("Claude", "Effort for tasks"))
        assertFalse(search.showsRow("In the car", "Quiet mode"))
    }

    @Test fun unlistedRowsFollowTheirSection() {
        assertTrue(SettingsSearch("repo").showsRow("Repositories", "mjparinas/aiauto"))
        assertFalse(SettingsSearch("theme").showsRow("Repositories", "mjparinas/aiauto"))
    }

    @Test fun rowsOutsideSettingsAlwaysShow() {
        assertTrue(SettingsSearch("theme").showsRow(null, "Add a template"))
    }

    @Test fun saysWhenNothingMatches() {
        assertTrue(SettingsSearch("zzzz").nothingFound)
        assertFalse(SettingsSearch("sync").nothingFound)
    }
}
