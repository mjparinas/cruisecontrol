package dev.aiauto.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The home screen and Settings share one search box, so the two stay styled alike. */
class SearchFieldTest {
    // Gradle runs app unit tests from the app module's directory.
    private fun screen(name: String) = File("src/main/java/dev/aiauto/ui/$name.kt").readText()

    @Test fun homeSearchUsesTheSharedSearchField() {
        val home = screen("HomeScreen")
        assertTrue("SearchField(query, \"Search task history\")" in home)
        assertFalse("OutlinedTextField(" in home)
    }

    @Test fun settingsSearchUsesTheSharedSearchField() {
        assertTrue("SearchField(query, \"Search settings\")" in screen("SettingsScreen"))
    }
}
