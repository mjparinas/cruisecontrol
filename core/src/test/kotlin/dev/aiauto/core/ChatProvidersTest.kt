package dev.aiauto.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChatProvidersTest {
    private val requests = mutableListOf<Request>()

    private fun http(code: Int, body: String) = OkHttpChatHttp(
        OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("HTTP $code")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build(),
    )

    @Test fun postsToEachProvidersOwnEndpointWithItsKey() {
        val body = buildJsonObject { put("model", "m") }
        assertEquals("yes", http(200, """{"ok": "yes"}""").post(ChatProvider.OpenAI, "sk-1", body)["ok"]!!.jsonPrimitive.content)
        http(200, "{}").post(ChatProvider.Gemini, "g-1", body)

        assertEquals("https://api.openai.com/v1/responses", requests[0].url.toString())
        assertEquals("Bearer sk-1", requests[0].header("Authorization"))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", requests[1].url.toString())
        assertEquals("Bearer g-1", requests[1].header("Authorization"))
    }

    @Test fun errorsCarryTheProvidersMessage() {
        val openAi = assertFailsWith<ChatApiException> {
            http(429, """{"error": {"message": "Slow down"}}""").post(ChatProvider.OpenAI, "k", buildJsonObject {})
        }
        assertEquals(429, openAi.code)
        assertEquals("Slow down", openAi.message)
        assertEquals("OpenAI is limiting requests right now. Try again in a minute.", Failures.describe(openAi))

        // Gemini sometimes wraps its error in a list.
        val gemini = assertFailsWith<ChatApiException> {
            http(400, """[{"error": {"message": "API key not valid"}}]""").post(ChatProvider.Gemini, "k", buildJsonObject {})
        }
        assertEquals("Gemini rejected the request: API key not valid", Failures.describe(gemini))
    }

    @Test fun anthropicHasNoChatEndpoint() {
        assertFailsWith<IllegalArgumentException> { http(200, "{}").post(ChatProvider.Anthropic, "k", buildJsonObject {}) }
        assertEquals(emptyList(), requests)
    }

    @Test fun savedClaudeModelsStillLoadAndUnknownOnesFallBackToOpus() {
        assertEquals(ChatModel.Haiku, ChatModel.of(ModelChoice.Haiku.id))
        assertEquals(ChatModel.GptSol, ChatModel.of("gpt-6.2-sol"))
        assertEquals(ChatModel.Opus, ChatModel.of("gpt-old"))
        assertEquals(ChatModel.Opus, ChatModel.of(null))
        ModelChoice.entries.forEach { claude ->
            val chat = ChatModel.of(claude.id)
            assertEquals(ChatProvider.Anthropic, chat.provider)
            assertEquals(claude.costUsd(1000, 2000), chat.costUsd(1000, 2000))
        }
    }
}
