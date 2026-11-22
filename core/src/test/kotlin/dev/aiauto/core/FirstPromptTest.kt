package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstPromptTest {
    private val task = Task("t1", RepoRef("mjparinas", "aiauto"), "Add a toggle", "claude/add-a-toggle-t1", createdMillis = 0, updatedMillis = 0)

    @Test fun asksClaudeToReadAndKeepTheRepositoryNotes() {
        val prompt = ClaudeAgents.firstPrompt(task, "add a toggle")
        assertTrue("if `CLAUDE.md` exists at the repository root, read it first" in prompt)
        assertTrue("If it's missing, create it." in prompt)
        assertTrue("Commit it with your change." in prompt)
    }

    @Test fun asksClaudeToReadTheReadmeNotesAndAgentsFileBeforeAnythingElse() {
        val prompt = ClaudeAgents.firstPrompt(task, "add a toggle")
        assertTrue("read README.md, CLAUDE.md and AGENTS.md at the repository root, whichever exist" in prompt)
        assertTrue(prompt.indexOf("Before anything else") < prompt.indexOf("Repository notes:"))
    }

    @Test fun notesComeBeforeTheRequest() {
        val prompt = ClaudeAgents.firstPrompt(task, "add a toggle")
        assertTrue(prompt.startsWith("Repository: mjparinas/aiauto, cloned at /workspace/repo."))
        assertTrue(prompt.indexOf(ClaudeAgents.NOTES_FILE) < prompt.indexOf("Request (spoken"))
        assertTrue(prompt.endsWith("\nadd a toggle"))
    }

    @Test fun repoRulesComeBetweenTheNotesAndTheRequest() {
        val prompt = ClaudeAgents.firstPrompt(task, "add a toggle", listOf("Use pnpm", "Leave the docs alone"))
        val rules = prompt.indexOf("Rules from the user for this repository")
        assertTrue(prompt.indexOf("Commit it with your change.") < rules)
        assertTrue(rules < prompt.indexOf("Request (spoken"))
        assertTrue("\n- Use pnpm\n- Leave the docs alone\n\nRequest" in prompt)
    }

    @Test fun noRulesSectionWithoutRepoRules() {
        val prompt = ClaudeAgents.firstPrompt(task, "add a toggle")
        assertFalse("Rules from the user" in prompt)
        assertTrue("Commit it with your change.\n\nRequest (spoken" in prompt)
    }

    @Test fun aMultiLineRequestIsKeptAsSpoken() {
        val prompt = ClaudeAgents.firstPrompt(task, "add a toggle\nand a test")
        assertTrue(prompt.startsWith("Repository: mjparinas/aiauto"))
        assertTrue(prompt.endsWith("\nadd a toggle\nand a test"))
    }

    @Test fun askModeForbidsEachWriteActionAndDoesNotAskToCommit() {
        val ask = task.copy(readOnly = true, title = "Where is spending?", branch = "ask/where-is-spending-t1")
        val prompt = ClaudeAgents.firstPrompt(ask, "where is spending tracked")
        val flat = prompt.replace(Regex("\\s+"), " ")
        assertTrue("Do not change repository files." in flat)
        assertTrue("Do not run write commands." in flat)
        assertTrue("Do not create a branch." in flat)
        assertTrue("Do not commit." in flat)
        assertTrue("Do not push." in flat)
        assertTrue("Do not open a pull request." in flat)
        assertFalse("Commit it with your change." in prompt)
        assertTrue("read-only Ask session" in prompt)
        assertTrue(prompt.endsWith("\nwhere is spending tracked"))
    }

    @Test fun askFollowUpsKeepTheReadOnlyRules() {
        val follow = ClaudeAgents.readOnlyFollowUp("where are the tests")
        assertTrue(follow.startsWith("This is still a read-only Ask session."))
        assertTrue("do not create a branch" in follow.lowercase())
        assertTrue(follow.endsWith("where are the tests"))
    }
}
