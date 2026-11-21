package dev.aiauto.core

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration

/**
 * Who answers in the car chat. Coding tasks always run as Claude Managed Agents sessions, which only Anthropic
 * offers, so another provider only changes the spoken chat. [endpoint] is null for Anthropic, which uses its SDK.
 */
enum class ChatProvider(val label: String, val assistantName: String, val keySite: String, val endpoint: String?) {
    Anthropic("Claude", "Claude", "platform.claude.com", null),
    OpenAI("OpenAI", "ChatGPT", "platform.openai.com", "https://api.openai.com/v1/responses"),
    Gemini("Gemini", "Gemini", "aistudio.google.com", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"),
}

/**
 * The models the car chat can use, with list prices in US dollars per million tokens. Only Claude requests ask for
 * prompt caching, so [cacheReadPerMTok] only matters there.
 */
enum class ChatModel(
    val provider: ChatProvider,
    val id: String,
    val label: String,
    val blurb: String,
    val inputPerMTok: Double,
    val outputPerMTok: Double,
    val cacheReadPerMTok: Double = inputPerMTok,
) {
    Opus(ModelChoice.Opus),
    Sonnet(ModelChoice.Sonnet),
    Haiku(ModelChoice.Haiku),
    GptAstra(ChatProvider.OpenAI, "gpt-6.2-astra", "GPT-6.2 Astra", "OpenAI's most capable", 8.0, 40.0),
    GptSol(ChatProvider.OpenAI, "gpt-6.2-sol", "GPT-6.2 Sol", "OpenAI, faster and a quarter of the price", 2.0, 10.0),
    GptLuna(ChatProvider.OpenAI, "gpt-6-luna", "GPT-6 Luna", "OpenAI's fastest and cheapest", 0.10, 0.50),
    // Google's launch price is half this until November 30, 2026. The later price keeps the daily limit on the safe side.
    GeminiCarbon(ChatProvider.Gemini, "gemini-4-carbon", "Gemini 4 Carbon", "Google's most capable", 4.0, 20.0),
    GeminiHydrogen(ChatProvider.Gemini, "gemini-4-hydrogen", "Gemini 4 Hydrogen", "Google, faster and cheaper", 1.50, 7.50),
    ;

    constructor(claude: ModelChoice) :
        this(ChatProvider.Anthropic, claude.id, claude.label, claude.blurb, claude.inputPerMTok, claude.outputPerMTok, claude.cacheReadPerMTok)

    fun costUsd(inputTokens: Long, outputTokens: Long, cacheWriteTokens: Long = 0, cacheReadTokens: Long = 0): Double =
        promptCostUsd(inputTokens, outputTokens, cacheWriteTokens, cacheReadTokens, inputPerMTok, outputPerMTok, cacheReadPerMTok)

    companion object {
        fun of(id: String?): ChatModel = entries.firstOrNull { it.id == id || it.name == id } ?: Opus
    }
}

/** A tool the car chat offers, described once and sent in each provider's own format. */
internal class ChatTool(
    val name: String,
    val description: String,
    /** Each parameter's name, JSON type and description. */
    val params: List<Triple<String, String, String>>,
    val required: List<String> = emptyList(),
) {
    fun schema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            params.forEach { (name, type, description) -> putJsonObject(name) { put("type", type); put("description", description) } }
        }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
    }
}

/** A tool call from the model. Arguments arrive as text, whatever their JSON type. */
internal class ToolCall(val id: String, val name: String, val args: Map<String, String>)

internal class ToolResult(val callId: String, val text: String, val isError: Boolean)

/** What one reply from the model said and asked for. No calls means the turn is over. */
internal class ChatTurn(val text: List<String>, val calls: List<ToolCall>, val refused: Boolean, val costUsd: Double)

/** One car chat conversation, kept in a provider's own message format so tool calls go back the way they came. */
internal interface ChatConversation {
    val size: Int
    fun addUser(text: String)
    fun send(system: String, tools: List<ChatTool>, chat: ChatProfile): ChatTurn
    fun addResults(results: List<ToolResult>)
    /** Drops everything after the first [size] entries, e.g. a turn that failed halfway. */
    fun rollback(size: Int)
    /** Keeps the conversation short. Only cuts right before a plain spoken question, so tool calls stay paired. */
    fun trim(max: Int)
    fun close() {}
}

/** Claude through the Messages API. */
internal class AnthropicConversation(private val client: AnthropicClient) : ChatConversation {
    private val history = mutableListOf<MessageParam>()
    override val size get() = history.size

    override fun addUser(text: String) { history += MessageParam.ofUser(text) }

    override fun send(system: String, tools: List<ChatTool>, chat: ChatProfile): ChatTurn {
        val message = client.messages().create(params(system, tools, chat, history.toList()))
        history += message.toParam()
        val stop = message.stopReason().orElse(null)
        val calls = if (stop != StopReason.TOOL_USE) emptyList() else message.content().mapNotNull { it.toolUse().orElse(null) }.map { call ->
            ToolCall(call.id(), call.name(), call._input().asObject().orElse(emptyMap()).mapNotNull { (k, v) -> v.text()?.let { k to it } }.toMap())
        }
        return ChatTurn(
            text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() },
            calls = calls,
            refused = stop == StopReason.REFUSAL,
            costUsd = costUsd(message, chat.model),
        )
    }

    override fun addResults(results: List<ToolResult>) {
        history += MessageParam.ofUserOfContentBlockParams(
            results.map { ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(it.callId).content(it.text).isError(it.isError).build()) },
        )
    }

    override fun rollback(size: Int) { while (history.size > size) history.removeAt(history.size - 1) }

    override fun trim(max: Int) = trimBefore(history, max) { it.role() == MessageParam.Role.USER && it.content().isString() }

    override fun close() = client.close()

    companion object {
        fun params(system: String, tools: List<ChatTool>, chat: ChatProfile, history: List<MessageParam>): MessageCreateParams =
            MessageCreateParams.builder()
                .model(chat.model.id)
                .maxTokens(MAX_TOKENS)
                // Low effort by default keeps replies quick, which matters more than depth while driving.
                .outputConfig(OutputConfig.builder().effort(effort(chat.effort)).build())
                // Automatic prompt caching: the breakpoint moves to the last block each turn, so the tools, system
                // prompt and earlier turns are read from the cache. The 5-minute lifetime fits a drive's back and forth.
                .cacheControl(CacheControlEphemeral.builder().build())
                .system(system)
                .apply { tools.forEach { addTool(it.toAnthropic()) } }
                .messages(history)
                .build()

        fun costUsd(message: Message, model: ChatModel): Double {
            val usage = message.usage()
            return model.costUsd(
                usage.inputTokens(),
                usage.outputTokens(),
                cacheWriteTokens = usage.cacheCreationInputTokens().orElse(0L),
                cacheReadTokens = usage.cacheReadInputTokens().orElse(0L),
            )
        }

        private fun ChatTool.toAnthropic(): Tool = Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder().apply {
                            params.forEach { (name, type, description) ->
                                putAdditionalProperty(name, JsonValue.from(mapOf("type" to type, "description" to description)))
                            }
                        }.build(),
                    )
                    .apply { if (required.isNotEmpty()) required(required) }
                    .build(),
            )
            .build()

        private fun JsonValue.text(): String? = asString().orElse(null)
            ?: asNumber().orElse(null)?.let { if (it.toDouble() % 1.0 == 0.0) it.toLong().toString() else it.toString() }
            ?: asBoolean().orElse(null)?.toString()

        private fun effort(effort: EffortChoice): OutputConfig.Effort = when (effort) {
            EffortChoice.Low -> OutputConfig.Effort.LOW
            EffortChoice.Medium -> OutputConfig.Effort.MEDIUM
            EffortChoice.High -> OutputConfig.Effort.HIGH
            EffortChoice.XHigh -> OutputConfig.Effort.XHIGH
            EffortChoice.Max -> OutputConfig.Effort.MAX
        }
    }
}

/**
 * OpenAI through the Responses API, which its current models need for tool calls. Nothing is stored on OpenAI's
 * side: each request sends the whole conversation, with the model's encrypted reasoning passed back as it came.
 */
internal class OpenAiConversation(private val key: String, private val http: ChatHttp) : ChatConversation {
    private val history = mutableListOf<JsonObject>()
    override val size get() = history.size

    override fun addUser(text: String) { history += plainUser(text) }

    override fun send(system: String, tools: List<ChatTool>, chat: ChatProfile): ChatTurn {
        val body = buildJsonObject {
            put("model", chat.model.id)
            put("instructions", system)
            put("input", JsonArray(history.toList()))
            putJsonArray("tools") {
                tools.forEach { t ->
                    add(buildJsonObject { put("type", "function"); put("name", t.name); put("description", t.description); put("parameters", t.schema()) })
                }
            }
            putJsonObject("reasoning") { put("effort", chat.effort.name.lowercase()) }
            put("max_output_tokens", MAX_TOKENS)
            put("store", false)
            putJsonArray("include") { add("reasoning.encrypted_content") }
        }
        val reply = http.post(ChatProvider.OpenAI, key, body)
        val output = reply.array("output").mapNotNull { it as? JsonObject }
        history += output
        val text = mutableListOf<String>()
        var refused = false
        output.filter { it.string("type") == "message" }.flatMap { it.array("content") }.mapNotNull { it as? JsonObject }.forEach { part ->
            when (part.string("type")) {
                "output_text" -> part.string("text")?.let { text += it }
                "refusal" -> { refused = true; part.string("refusal")?.let { text += it } }
            }
        }
        val calls = output.filter { it.string("type") == "function_call" }.map { ToolCall(it.string("call_id").orEmpty(), it.string("name").orEmpty(), parseArgs(it.string("arguments"))) }
        val usage = reply.obj("usage")
        val cost = chat.model.costUsd(usage.long("input_tokens"), usage.long("output_tokens"))
        return ChatTurn(text, if (refused) emptyList() else calls, refused, cost)
    }

    override fun addResults(results: List<ToolResult>) {
        results.forEach { r ->
            history += buildJsonObject {
                put("type", "function_call_output")
                put("call_id", r.callId)
                put("output", if (r.isError) "Error: ${r.text}" else r.text)
            }
        }
    }

    override fun rollback(size: Int) { while (history.size > size) history.removeAt(history.size - 1) }

    override fun trim(max: Int) = trimBefore(history, max, ::isPlainUser)
}

/**
 * Gemini through Google's OpenAI-compatible Chat Completions endpoint. The model's messages go back exactly as they
 * came, because Gemini's tool calls carry thought signatures it needs to see again.
 */
internal class GeminiConversation(private val key: String, private val http: ChatHttp) : ChatConversation {
    private val history = mutableListOf<JsonObject>()
    override val size get() = history.size

    override fun addUser(text: String) { history += plainUser(text) }

    override fun send(system: String, tools: List<ChatTool>, chat: ChatProfile): ChatTurn {
        val body = buildJsonObject {
            put("model", chat.model.id)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                history.forEach { add(it) }
            }
            putJsonArray("tools") {
                tools.forEach { t ->
                    add(
                        buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") { put("name", t.name); put("description", t.description); put("parameters", t.schema()) }
                        },
                    )
                }
            }
            // Gemini's thinking levels stop at high.
            put("reasoning_effort", if (chat.effort == EffortChoice.Low || chat.effort == EffortChoice.Medium) chat.effort.name.lowercase() else "high")
            put("max_tokens", MAX_TOKENS)
        }
        val reply = http.post(ChatProvider.Gemini, key, body)
        val choice = reply.array("choices").firstOrNull() as? JsonObject
        val message = choice?.obj("message") ?: JsonObject(emptyMap())
        history += JsonObject(message.filterValues { it !is JsonNull })
        val calls = message.array("tool_calls").mapNotNull { it as? JsonObject }.map { call ->
            val function = call.obj("function")
            ToolCall(call.string("id").orEmpty(), function.string("name").orEmpty(), parseArgs(function.string("arguments")))
        }
        val text = listOfNotNull(message.string("content") ?: message.string("refusal"))
        val usage = reply.obj("usage")
        val input = usage.long("prompt_tokens")
        // Thinking counts as output. The total includes it even where the completion count doesn't.
        val output = maxOf(usage.long("completion_tokens"), usage.long("total_tokens") - input)
        val refused = message.string("refusal") != null && message.string("content") == null
        return ChatTurn(text, calls, refused, chat.model.costUsd(input, output))
    }

    override fun addResults(results: List<ToolResult>) {
        results.forEach { r ->
            history += buildJsonObject {
                put("role", "tool")
                put("tool_call_id", r.callId)
                put("content", if (r.isError) "Error: ${r.text}" else r.text)
            }
        }
    }

    override fun rollback(size: Int) { while (history.size > size) history.removeAt(history.size - 1) }

    override fun trim(max: Int) = trimBefore(history, max, ::isPlainUser)
}

/** Posts a JSON request to a chat provider and returns its JSON reply. An interface so tests can answer instead. */
fun interface ChatHttp {
    fun post(provider: ChatProvider, key: String, body: JsonObject): JsonObject
}

/** An error reply from a chat provider other than Anthropic. */
class ChatApiException(val provider: ChatProvider, val code: Int, message: String) : RuntimeException(message)

/** The real [ChatHttp]. The key is only ever sent to the provider's own endpoint. */
class OkHttpChatHttp(
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(60)).build(),
) : ChatHttp {
    override fun post(provider: ChatProvider, key: String, body: JsonObject): JsonObject {
        val url = requireNotNull(provider.endpoint) { "${provider.label} has no chat endpoint." }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw ChatApiException(provider, response.code, errorMessage(text) ?: "HTTP ${response.code}")
            return runCatching { Json.parseToJsonElement(text) as JsonObject }
                .getOrElse { throw ChatApiException(provider, response.code, "${provider.label} sent a reply the app couldn't read.") }
        }
    }

    private fun errorMessage(text: String): String? {
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
        // Gemini sometimes wraps its error in a list.
        val root = (parsed as? JsonArray)?.firstOrNull() ?: parsed
        return ((root as? JsonObject)?.get("error") as? JsonObject)?.string("message")
    }
}

private const val MAX_TOKENS = 4000L

private fun plainUser(text: String) = buildJsonObject { put("role", "user"); put("content", text) }

private fun isPlainUser(item: JsonObject) = item.string("role") == "user" && (item["content"] as? JsonPrimitive)?.isString == true

private fun <T> trimBefore(history: MutableList<T>, max: Int, isPlainUser: (T) -> Boolean) {
    if (history.size <= max) return
    val cut = (history.size - max until history.size).firstOrNull { isPlainUser(history[it]) } ?: return
    repeat(cut) { history.removeAt(0) }
}

/** Tool arguments as text. Whole numbers lose their ".0" so "12" and 12 read the same. */
private fun parseArgs(arguments: String?): Map<String, String> {
    val parsed = arguments?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return emptyMap()
    return parsed.mapNotNull { (name, value) ->
        val primitive = value as? JsonPrimitive ?: return@mapNotNull null
        if (primitive is JsonNull) return@mapNotNull null
        val text = when {
            primitive.isString -> primitive.content
            primitive.longOrNull != null -> primitive.longOrNull.toString()
            primitive.doubleOrNull?.let { it % 1.0 == 0.0 } == true -> primitive.doubleOrNull!!.toLong().toString()
            else -> primitive.content
        }
        name to text
    }.toMap()
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.long(name: String): Long = (this[name] as? JsonPrimitive)?.longOrNull ?: 0L
private fun JsonObject.obj(name: String): JsonObject = this[name] as? JsonObject ?: JsonObject(emptyMap())
private fun JsonObject.array(name: String): List<JsonElement> = this[name] as? JsonArray ?: emptyList()
