package dev.aiauto.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class AssistantTest {
    private val started = mutableListOf<Pair<String, String>>()
    private val startedIssues = mutableListOf<Pair<String, Int>>()
    private val startedLater = mutableListOf<Triple<String, String, Int>>()
    private val issue = GitHubIssue(34, "Fix login", "2026-01-02T03:04:05Z", body = "The login fails.")

    private val actions = object : AssistantActions {
        override fun context() = "none"
        override fun startTask(repo: String, request: String): String {
            started += repo to request
            return "Started task ab12 in $repo."
        }
        override fun startTaskLater(repo: String, request: String, minutes: Int): String {
            startedLater += Triple(repo, request, minutes)
            return "Saved a task in $repo for later."
        }
        override fun startIssue(repo: String, issue: GitHubIssue): String {
            startedIssues += repo to issue.number
            return "Started issue ${issue.number} in $repo."
        }
        override fun issue(repo: String, number: Int): GitHubIssue {
            assertEquals(34, number)
            return issue
        }
        override fun openIssues(repo: String) = "Open issues in $repo: Issue 34: Fix login."
        override fun quiet() = "Quiet mode is on."
        override fun replyToTask(taskId: String, text: String) = fail("not expected")
        override fun addRepo(name: String) = fail("not expected")
    }

    /** Answers chat requests from a queue of canned replies, and keeps what was sent. */
    private class FakeHttp(vararg replies: String) : ChatHttp {
        val queue = ArrayDeque(replies.toList())
        val sent = mutableListOf<Pair<ChatProvider, JsonObject>>()
        var failWith: Throwable? = null

        override fun post(provider: ChatProvider, key: String, body: JsonObject): JsonObject {
            assertEquals("key-${provider.name}", key)
            sent += provider to body
            failWith?.let { failWith = null; throw it }
            return Json.parseToJsonElement(queue.removeFirst()).jsonObject
        }
    }

    private fun assistant(
        model: ChatModel,
        http: ChatHttp,
        spent: MutableList<Double> = mutableListOf(),
        effort: EffortChoice = EffortChoice.Low,
        now: () -> Long = { 0L },
    ) = Assistant(
        apiKey = { "key-${it.name}" },
        actions = actions,
        onSpend = { spent += it },
        profile = { ChatProfile(model, effort) },
        clock = now,
        clientFactory = { fail("Claude isn't the chosen model") },
        http = http,
    )

    @Test
    fun asksForSetupWithoutKey() {
        val assistant = Assistant(apiKey = { null }, actions = actions, clientFactory = { fail("no client without a key") })
        assertTrue("API key" in assistant.ask("hello"))
    }

    @Test
    fun asksForTheChosenProvidersKey() {
        val assistant = Assistant(
            apiKey = { if (it == ChatProvider.Anthropic) "k" else null },
            actions = actions,
            profile = { ChatProfile(ChatModel.GeminiHydrogen) },
            clientFactory = { fail("Gemini was chosen") },
            http = { _, _, _ -> fail("no call without a Gemini key") },
        )
        assertEquals("Add your Gemini API key in the app on your phone first.", assistant.ask("hello"))
    }

    @Test
    fun stopsAtDailyLimit() {
        val assistant = Assistant(apiKey = { "k" }, actions = actions, canSpend = { false }, clientFactory = { fail("no call over the limit") })
        assertTrue("spending limit" in assistant.ask("hello"))
    }

    @Test
    fun openAiCallsToolsThroughTheResponsesApi() {
        val http = FakeHttp(
            """{"output": [
                {"type": "reasoning", "id": "rs_1", "encrypted_content": "secret"},
                {"type": "function_call", "id": "fc_1", "call_id": "call_1", "name": "start_coding_task",
                 "arguments": "{\"repo\": \"me/app\", \"request\": \"Add dark mode\"}"}
            ], "usage": {"input_tokens": 1000000, "output_tokens": 0}}""",
        )
        val spent = mutableListOf<Double>()
        val assistant = assistant(ChatModel.GptSol, http, spent)
        val answer = assistant.ask("Add dark mode to app")

        assertEquals("I heard: Add dark mode in me/app. Say yes to start, or no to cancel.", answer)
        assertTrue(started.isEmpty(), "the task must wait for a separate confirmation")
        assertEquals("Started task ab12 in me/app.", assistant.ask("yes"))
        assertEquals(listOf("me/app" to "Add dark mode"), started)
        assertEquals(listOf(2.0), spent)
        val (provider, first) = http.sent[0]
        assertEquals(ChatProvider.OpenAI, provider)
        assertEquals("gpt-6.2-sol", first["model"]!!.jsonPrimitive.content)
        assertEquals("low", first["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("false", first["store"]!!.jsonPrimitive.content)
        assertTrue(first["instructions"]!!.jsonPrimitive.content.startsWith("You are ChatGPT,"))
        assertEquals(8, first["tools"]!!.jsonArray.size)
        assertEquals(1, http.sent.size, "confirmation should not start another model turn")
    }

    @Test
    fun anIssueTaskWaitsForConfirmation() {
        val http = FakeHttp(
            """{"output": [{"type": "function_call", "id": "fc_1", "call_id": "call_1", "name": "start_coding_task",
                "arguments": "{\"repo\":\"me/app\",\"issue_number\":34}"}], "usage": {}}""",
        )
        val assistant = assistant(ChatModel.GptSol, http)

        assertEquals(
            "I heard: fix issue 34, Fix login in me/app. Say yes to start, or no to cancel.",
            assistant.ask("Fix issue 34 in app"),
        )
        assertTrue(startedIssues.isEmpty())
        assertEquals("Started issue 34 in me/app.", assistant.ask("yes"))
        assertEquals(listOf("me/app" to 34), startedIssues)
    }

    @Test
    fun repeatAndQuietAreHandledWithoutAnotherChatTurn() {
        val http = FakeHttp(
            """{"output": [{"type": "message", "content": [{"type": "output_text", "text": "The build is running."}]}], "usage": {}}""",
        )
        val assistant = assistant(ChatModel.GptLuna, http)

        assertEquals("The build is running.", assistant.ask("What's happening?"))
        assertEquals("The build is running.", assistant.ask("repeat that"))
        assertEquals("Quiet mode is on.", assistant.ask("quiet"))
        assertEquals(1, http.sent.size)
    }

    @Test
    fun aTaskForLaterIsReadBackWithItsDelayAndSavedOnYes() {
        val http = FakeHttp(
            """{"output": [{"type": "function_call", "id": "fc_1", "call_id": "call_1", "name": "start_coding_task",
                "arguments": "{\"repo\":\"me/app\",\"request\":\"Update the dependencies\",\"start_in_minutes\":150}"}], "usage": {}}""",
        )
        val assistant = assistant(ChatModel.GptLuna, http)
        assertEquals(
            "I heard: Update the dependencies in me/app. Say yes to save it for 2 hours and 30 minutes from now, or no to cancel.",
            assistant.ask("Update the dependencies in app tonight"),
        )
        assertEquals("Saved a task in me/app for later.", assistant.ask("yes"))
        assertEquals(listOf(Triple("me/app", "Update the dependencies", 150)), startedLater)
        assertTrue(started.isEmpty())
    }

    @Test
    fun aTaskConfirmationExpiresInsteadOfStartingLater() {
        val http = FakeHttp(
            """{"output": [{"type": "function_call", "id": "fc_1", "call_id": "call_1", "name": "start_coding_task",
                "arguments": "{\"repo\":\"me/app\",\"request\":\"Fix the build\"}"}], "usage": {}}""",
        )
        var now = 0L
        val assistant = assistant(ChatModel.GptLuna, http, now = { now })
        assistant.ask("Fix the build in app")
        now = 60_001L

        assertEquals("That confirmation expired. Ask me to start the task again.", assistant.ask("yes"))
        assertTrue(started.isEmpty())
    }

    @Test
    fun geminiSendsItsMessagesBackWithThoughtSignatures() {
        val http = FakeHttp(
            """{"choices": [{"finish_reason": "tool_calls", "message": {"role": "assistant", "content": null, "tool_calls": [
                {"id": "c1", "type": "function", "function": {"name": "start_coding_task", "arguments": "{\"repo\":\"me/app\",\"request\":\"Fix the build\"}"},
                 "extra_content": {"google": {"thought_signature": "sig"}}}
            ]}}], "usage": {"prompt_tokens": 100, "completion_tokens": 10, "total_tokens": 150}}""",
        )
        val spent = mutableListOf<Double>()
        val assistant = assistant(ChatModel.GeminiHydrogen, http, spent, EffortChoice.Max)
        val answer = assistant.ask("Fix the build in app")

        assertEquals("I heard: Fix the build in me/app. Say yes to start, or no to cancel.", answer)
        assertTrue(started.isEmpty(), "the task must wait for a separate confirmation")
        assertEquals("Started task ab12 in me/app.", assistant.ask("yes"))
        assertEquals(listOf("me/app" to "Fix the build"), started)
        // Thinking counts as output: 150 total minus 100 prompt is 50, not the 10 completion tokens.
        assertEquals(ChatModel.GeminiHydrogen.costUsd(100, 50), spent[0])
        val first = http.sent[0].second
        assertEquals("high", first["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("gemini-4-hydrogen", first["model"]!!.jsonPrimitive.content)
        assertEquals(1, http.sent.size, "confirmation should not start another model turn")
        assertEquals("gemini-4-hydrogen", http.sent[0].second["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun aFailedTurnIsSpokenAndForgotten() {
        val http = FakeHttp(
            """{"output": [{"type": "message", "content": [{"type": "output_text", "text": "Hi."}]}], "usage": {}}""",
        )
        http.failWith = ChatApiException(ChatProvider.OpenAI, 401, "Incorrect API key")
        val assistant = assistant(ChatModel.GptLuna, http)

        assertEquals("OpenAI rejected your API key. Check it in Settings.", assistant.ask("first"))
        assertEquals("Hi.", assistant.ask("second"))
        val input = http.sent[1].second["input"]!!.jsonArray
        assertEquals(listOf("second"), input.map { it.jsonObject["content"]!!.jsonPrimitive.content })
    }

    /** Fails with no connection while [offline] is true, and otherwise answers with the question it was asked. */
    private class SignalHttp : ChatHttp {
        var offline = true
        override fun post(provider: ChatProvider, key: String, body: JsonObject): JsonObject {
            if (offline) throw java.net.UnknownHostException("api.openai.com")
            val asked = body["input"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
            return Json.parseToJsonElement(
                """{"output": [{"type": "message", "content": [{"type": "output_text", "text": "Answer to $asked."}]}], "usage": {}}""",
            ).jsonObject
        }
    }

    private fun offlineAssistant(http: ChatHttp, now: () -> Long = { 0L }) = Assistant(
        apiKey = { "key-${it.name}" },
        actions = actions,
        profile = { ChatProfile(ChatModel.GptLuna) },
        clock = now,
        clientFactory = { fail("Claude isn't the chosen model") },
        http = http,
    )

    @Test
    fun aQuestionWithNoSignalIsQueuedAndAnsweredWhenBackOnline() {
        val http = SignalHttp()
        val assistant = offlineAssistant(http)

        assertEquals(Assistant.QUEUED, assistant.ask("What's running?"))
        assertEquals(Assistant.QUEUED, assistant.ask("Any failed checks"))
        assertTrue(assistant.hasQueued())
        assertEquals(emptyList(), assistant.sendQueued(), "still offline, so nothing is sent")
        assertTrue(assistant.hasQueued())

        http.offline = false
        assertEquals(
            listOf("You asked: What's running. Answer to What's running?.", "You asked: Any failed checks. Answer to Any failed checks."),
            assistant.sendQueued(),
        )
        assertFalse(assistant.hasQueued())
        assertEquals(emptyList(), assistant.sendQueued())
    }

    @Test
    fun permanentFailuresAreNotQueued() {
        val http = FakeHttp()
        http.failWith = ChatApiException(ChatProvider.OpenAI, 401, "Incorrect API key")
        val assistant = assistant(ChatModel.GptLuna, http)

        assertEquals("OpenAI rejected your API key. Check it in Settings.", assistant.ask("hello"))
        assertFalse(assistant.hasQueued())
    }

    @Test
    fun briefServerErrorsAreQueuedLikeAnOutage() {
        val http = FakeHttp(
            """{"output": [{"type": "message", "content": [{"type": "output_text", "text": "Hello back."}]}], "usage": {}}""",
        )
        http.failWith = ChatApiException(ChatProvider.OpenAI, 500, "down")
        val assistant = assistant(ChatModel.GptLuna, http)

        assertEquals(Assistant.QUEUED, assistant.ask("hello"))
        assertTrue(assistant.hasQueued())
        assertEquals(listOf("You asked: hello. Hello back."), assistant.sendQueued())
        assertFalse(assistant.hasQueued())
    }

    @Test
    fun aTaskStartWaitsForConfirmationWithoutRetryingTheChatRequest() {
        var calls = 0
        val http = ChatHttp { _, _, _ ->
            if (calls++ > 0) throw java.io.IOException("connection reset")
            Json.parseToJsonElement(
                """{"output": [{"type": "function_call", "id": "fc_1", "call_id": "call_1", "name": "start_coding_task",
                    "arguments": "{\"repo\": \"me/app\", \"request\": \"Add dark mode\"}"}], "usage": {}}""",
            ).jsonObject
        }
        val assistant = offlineAssistant(http)

        assertEquals("I heard: Add dark mode in me/app. Say yes to start, or no to cancel.", assistant.ask("Add dark mode to app"))
        assertFalse(assistant.hasQueued(), "sending it again would start the task twice")
        assertTrue(started.isEmpty())
        assertEquals("Started task ab12 in me/app.", assistant.ask("yes"))
        assertEquals(listOf("me/app" to "Add dark mode"), started)
        assertEquals(1, calls, "the confirmation is handled locally")
    }

    @Test
    fun oldQueuedQuestionsAreDroppedAndTheQueueIsCapped() {
        val http = SignalHttp()
        var now = 0L
        val assistant = offlineAssistant(http) { now }

        assertEquals(Assistant.QUEUED, assistant.ask("old one"))
        now = Assistant.QUEUE_MAX_AGE_MILLIS
        repeat(Assistant.MAX_QUEUED - 1) { assertEquals(Assistant.QUEUED, assistant.ask("new $it")) }
        assertTrue("already holding" in assistant.ask("one too many"))

        now += 1
        http.offline = false
        val answers = assistant.sendQueued()
        assertEquals(Assistant.MAX_QUEUED - 1, answers.size)
        assertTrue(answers.none { "old one" in it })
    }
}
