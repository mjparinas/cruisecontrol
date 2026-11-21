package dev.aiauto.core

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.MessageParam
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
import kotlin.test.assertTrue

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
            assertEquals(claude.costUsd(1000, 2000, 300, 4000), chat.costUsd(1000, 2000, 300, 4000))
        }
    }

    @Test fun claudeChatRequestsTurnOnAutomaticPromptCaching() {
        val params = AnthropicConversation.params("Be brief.", emptyList(), ChatProfile(), listOf(MessageParam.ofUser("Hi")))
        val cache = params.cacheControl().orElseThrow()
        assertEquals(JsonValue.from("ephemeral"), cache._type())
        // No TTL means the default 5-minute lifetime, which costs 1.25 times input to write instead of 2.
        assertTrue(cache.ttl().isEmpty)
    }

    @Test fun cacheWritesCostMoreThanInputAndReadsCostLess() {
        // One million tokens of each kind, so the cost is the price per million.
        val m = 1_000_000L
        assertEquals(4.0, ChatModel.Opus.costUsd(m, 0), 1e-9)
        assertEquals(5.0, ChatModel.Opus.costUsd(0, 0, cacheWriteTokens = m), 1e-9)
        assertEquals(0.20, ChatModel.Opus.costUsd(0, 0, cacheReadTokens = m), 1e-9)
        assertEquals(2.50, ChatModel.Sonnet.costUsd(0, 0, cacheWriteTokens = m), 1e-9)
        assertEquals(0.10, ChatModel.Sonnet.costUsd(0, 0, cacheReadTokens = m), 1e-9)
        assertEquals(0.125, ChatModel.Haiku.costUsd(0, 0, cacheWriteTokens = m), 1e-9)
        assertEquals(0.01, ChatModel.Haiku.costUsd(0, 0, cacheReadTokens = m), 1e-9)
        assertEquals(4.0 + 5.0 + 0.20 + 20.0, ChatModel.Opus.costUsd(m, m, m, m), 1e-9)
    }
}
