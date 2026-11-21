package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertTrue

class FirstPromptTest {
    private val task = Task("t1", RepoRef("mjparinas", "aiauto"), "Add a toggle", "claude/add-a-toggle-t1", createdMillis = 0, updatedMillis = 0)

    @Test fun asksClaudeToReadAndKeepTheRepositoryNotes() {
        val prompt = ClaudeAgents.firstPrompt(task, "add a toggle")
        assertTrue("if `CLAUDE.md` exists at the repository root, read it first" in prompt)
        assertTrue("If it's missing, create it." in prompt)
        assertTrue("Commit it with your change." in prompt)
    }

    @Test fun notesComeBeforeTheRequest() {
        val prompt = ClaudeAgents.firstPrompt(task, "add a toggle")
        assertTrue(prompt.startsWith("Repository: mjparinas/aiauto, cloned at /workspace/repo."))
        assertTrue(prompt.indexOf(ClaudeAgents.NOTES_FILE) < prompt.indexOf("Request (spoken"))
        assertTrue(prompt.endsWith("\nadd a toggle"))
    }
}
