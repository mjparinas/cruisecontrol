package dev.aiauto.core

import kotlinx.serialization.Serializable

/** A saved, reusable request, such as "bump dependencies", startable in one tap on the phone or in the car. */
@Serializable
data class TaskTemplate(
    val id: String,
    /** Short name shown on buttons and said by voice, like "Fix lint". */
    val name: String,
    /** What Claude is asked to do. */
    val prompt: String,
) {
    companion object {
        /** Templates new installs start with. */
        val defaults = listOf(
            TaskTemplate(
                "bump-deps", "Bump dependencies",
                "Update the project's dependencies to their latest stable versions. Fix anything the upgrade breaks, " +
                    "run the build and tests, and skip any upgrade that needs a big rewrite, saying which in the pull request.",
            ),
            TaskTemplate(
                "fix-lint", "Fix lint",
                "Run the project's linters and formatters and fix every warning and error they report, without changing behaviour. " +
                    "Run the tests afterwards.",
            ),
            TaskTemplate(
                "add-tests", "Add missing tests",
                "Find the most important code that has no tests and add focused tests for it. Don't change the code under test " +
                    "unless a test finds a real bug, and say so if it does.",
            ),
        )

        /** The template the driver named, ignoring case, spaces and punctuation. Null when none or several match. */
        fun matchSpoken(spoken: String, templates: List<TaskTemplate>): TaskTemplate? {
            fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
            val said = norm(spoken)
            if (said.isEmpty()) return null
            templates.firstOrNull { norm(it.name) == said }?.let { return it }
            return templates.filter { norm(it.name).contains(said) || said.contains(norm(it.name)) }.singleOrNull()
        }
    }
}
