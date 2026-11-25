package dev.aiauto.car

import androidx.car.app.model.Action
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The car screens moved from each template's own title, header action and action strip to [carHeader] (Car App 1.7).
 * The templates check what a header may hold when they're built, so these build each kind the app uses.
 */
class CarHeaderTest {
    private fun action(title: String) = Action.Builder().setTitle(title).setOnClickListener {}.build()

    @Test fun aListScreenTakesABackArrowAndTwoEndActions() {
        val template = ListTemplate.Builder()
            .setHeader(carHeader("Claude tasks", Action.APP_ICON, listOf(action("New task"), action("Projects"))))
            .setLoading(true)
            .build()
        assertEquals(2, template.header!!.endHeaderActions.size)
        assertEquals(Action.APP_ICON, template.header!!.startHeaderAction)
    }

    @Test fun aMessageScreenKeepsItsOwnActionsBesideTheHeader() {
        val template = MessageTemplate.Builder("Say what you want Claude to do.")
            .setHeader(carHeader("Claude", Action.APP_ICON, listOf(action("New chat"), action("Tasks"))))
            .addAction(action("Talk"))
            .build()
        assertEquals("Claude", template.header!!.title.toString())
        assertEquals(1, template.actions.size)
    }

    @Test fun aPaneScreenTakesTheTaskScreensTwoActions() {
        val pane = Pane.Builder().addRow(Row.Builder().setTitle("Working").build()).addAction(action("Reply")).build()
        val template = PaneTemplate.Builder(pane)
            .setHeader(carHeader("Add a test", end = listOf(action("Read aloud"), action("History"))))
            .build()
        assertEquals(Action.BACK, template.header!!.startHeaderAction)
        assertEquals(2, template.header!!.endHeaderActions.size)
    }

    @Test fun noCarScreenUsesTheOldTemplateHeaderSetters() {
        // Gradle runs app unit tests from the app module's directory.
        val screens = File("src/main/java/dev/aiauto/car").listFiles { f -> f.name.endsWith("Screen.kt") }.orEmpty()
        assertTrue(screens.isNotEmpty())
        screens.forEach { file ->
            val text = file.readText()
            assertTrue(".setHeaderAction(" !in text, "${file.name} still calls setHeaderAction")
            assertTrue(".setActionStrip(" !in text, "${file.name} still calls setActionStrip")
        }
    }
}
