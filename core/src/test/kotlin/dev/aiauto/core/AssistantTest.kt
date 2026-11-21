package dev.aiauto.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class AssistantTest {
    private val started = mutableListOf<Pair<String, String>>()

    private val actions = object : AssistantActions {
        override fun context() = "none"
        override fun startTask(repo: String, request: String): String {
            started += repo to request
            return "Started task ab12 in $repo."
        }
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

    private fun assistant(model: ChatModel, http: ChatHttp, spent: MutableList<Double> = mutableListOf(), effort: EffortChoice = EffortChoice.Low) = Assistant(
        apiKey = { "key-${it.name}" },
        actions = actions,
        onSpend = { spent += it },
        profile = { ChatProfile(model, effort) },
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
            """{"output": [{"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": "Started dark mode in app."}]}],
                "usage": {"input_tokens": 0, "output_tokens": 1000000}}""",
        )
        val spent = mutableListOf<Double>()
        val answer = assistant(ChatModel.GptSol, http, spent).ask("Add dark mode to app")

        assertEquals("Started dark mode in app.", answer)
        assertEquals(listOf("me/app" to "Add dark mode"), started)
        assertEquals(listOf(2.0, 10.0), spent)
        val (provider, first) = http.sent[0]
        assertEquals(ChatProvider.OpenAI, provider)
        assertEquals("gpt-6.2-sol", first["model"]!!.jsonPrimitive.content)
        assertEquals("low", first["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("false", first["store"]!!.jsonPrimitive.content)
        assertTrue(first["instructions"]!!.jsonPrimitive.content.startsWith("You are ChatGPT,"))
        assertEquals(6, first["tools"]!!.jsonArray.size)
        // The second request carries the reasoning and the call back, followed by the tool's answer.
        val input = http.sent[1].second["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", null, null, null), input.map { it["role"]?.jsonPrimitive?.content })
        assertEquals("secret", input[1]["encrypted_content"]!!.jsonPrimitive.content)
        assertEquals("function_call_output", input[3]["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", input[3]["call_id"]!!.jsonPrimitive.content)
        assertEquals("Started task ab12 in me/app.", input[3]["output"]!!.jsonPrimitive.content)
    }

    @Test
    fun geminiSendsItsMessagesBackWithThoughtSignatures() {
        val http = FakeHttp(
            """{"choices": [{"finish_reason": "tool_calls", "message": {"role": "assistant", "content": null, "tool_calls": [
                {"id": "c1", "type": "function", "function": {"name": "start_coding_task", "arguments": "{\"repo\":\"me/app\",\"request\":\"Fix the build\"}"},
                 "extra_content": {"google": {"thought_signature": "sig"}}}
            ]}}], "usage": {"prompt_tokens": 100, "completion_tokens": 10, "total_tokens": 150}}""",
            """{"choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": "On it."}}],
                "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}}""",
        )
        val spent = mutableListOf<Double>()
        val answer = assistant(ChatModel.GeminiHydrogen, http, spent, EffortChoice.Max).ask("Fix the build in app")

        assertEquals("On it.", answer)
        assertEquals(listOf("me/app" to "Fix the build"), started)
        // Thinking counts as output: 150 total minus 100 prompt is 50, not the 10 completion tokens.
        assertEquals(ChatModel.GeminiHydrogen.costUsd(100, 50), spent[0])
        val first = http.sent[0].second
        assertEquals("high", first["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("gemini-4-hydrogen", first["model"]!!.jsonPrimitive.content)
        val messages = http.sent[1].second["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertTrue(messages[0]["content"]!!.jsonPrimitive.content.startsWith("You are Gemini,"))
        val call = (messages[2]["tool_calls"] as JsonArray)[0].jsonObject
        assertEquals("sig", call["extra_content"]!!.jsonObject["google"]!!.jsonObject["thought_signature"]!!.jsonPrimitive.content)
        assertEquals("c1", messages[3]["tool_call_id"]!!.jsonPrimitive.content)
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
}
