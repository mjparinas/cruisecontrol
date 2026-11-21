package dev.aiauto.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import java.time.Duration

/** What the voice assistant can do in the app, beyond talking. The app wires these to [TaskEngine]. */
interface AssistantActions {
    /** A short plain-text summary of the saved repos and recent tasks, given to Claude on every turn. */
    fun context(): String

    /** Starts a coding task. Returns what to tell Claude, such as "Started task ab12cd34 in owner/repo". */
    fun startTask(repo: String, request: String): String

    /** Sends a follow-up message to an existing task. Returns what to tell Claude. */
    fun replyToTask(taskId: String, text: String): String

    /** Saves a repo from its spoken name (owner/name or just the name). Returns what to tell Claude. */
    fun addRepo(name: String): String

    /** Where a task, a pull request, a repo, or everything that's going stands. Blank or null means not given. */
    fun status(repo: String, prNumber: Int?, taskId: String): String = throw UnsupportedOperationException("Status isn't available.")

    /** Stops a task, or the only running one when [taskId] is blank. Returns what to tell Claude. */
    fun stopTask(taskId: String): String = throw UnsupportedOperationException("Stopping isn't available.")

    /** Checks whether a pull request can merge, without merging it. */
    fun previewMerge(repo: String, prNumber: Int?): MergePreview = throw UnsupportedOperationException("Merging isn't available.")

    /** Merges a pull request the driver confirmed. Returns what to tell Claude. */
    fun merge(repo: RepoRef, number: Int): String = throw UnsupportedOperationException("Merging isn't available.")
}

/**
 * A spoken conversation with Claude for the car's mic button. Each [ask] takes what the driver said and
 * returns what Claude says back, ready to be read aloud. Claude can also start a coding task or reply
 * to one through [AssistantActions]. Blocking: call it off the main thread.
 */
class Assistant(
    private val apiKey: () -> String?,
    private val actions: AssistantActions,
    private val canSpend: () -> Boolean = { true },
    private val onSpend: (Double) -> Unit = {},
    private val profile: () -> ChatProfile = { ChatProfile() },
    private val memory: () -> ChatMemory = { NoChatMemory },
    private val clock: () -> Long = System::currentTimeMillis,
    private val clientFactory: (String) -> AnthropicClient = ::defaultClient,
) {
    private val history = mutableListOf<MessageParam>()
    /** Exchanges remembered before this start are from earlier drives, so they go in the system prompt. */
    private var startedMillis = clock()
    /** Counts the driver's questions, so a merge can only be confirmed by a later one. */
    private var turn = 0
    private val merges = MergeConfirmation(actions)
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    @Synchronized
    fun ask(spoken: String): String {
        val key = apiKey() ?: return "Add your Anthropic API key in the app on your phone first."
        if (!canSpend()) return "You've reached your daily or monthly spending limit, so I can't answer right now. You can raise it in Settings on your phone."
        val start = history.size
        return try {
            val claude = clientFor(key)
            turn++
            history += MessageParam.ofUser(spoken)
            converse(claude).also { reply ->
                runCatching { memory().remember(ChatExchange(spoken, reply, clock())) }
            }
        } catch (e: Throwable) {
            // Drop the half-finished turn so the next question starts from a clean history.
            while (history.size > start) history.removeAt(history.size - 1)
            Failures.describe(e)
        }
    }

    /** Forgets the conversation, e.g. when the driver starts over. */
    @Synchronized
    fun reset() {
        history.clear()
        startedMillis = clock()
        merges.clear()
    }

    private fun converse(claude: AnthropicClient): String {
        val spoken = StringBuilder()
        repeat(MAX_TOOL_ROUNDS) {
            val chat = profile()
            val message = claude.messages().create(params(chat))
            onSpend(costUsd(message, chat.model))
            history += message.toParam()
            message.content().forEach { block -> block.text().ifPresent { spoken.appendSentence(it.text()) } }
            val stop = message.stopReason().orElse(null)
            if (stop == StopReason.REFUSAL) return spoken.ifBlank { "I can't help with that one." }.toString()
            val calls = message.content().mapNotNull { it.toolUse().orElse(null) }
            if (stop != StopReason.TOOL_USE || calls.isEmpty()) {
                trimHistory()
                return spoken.ifBlank { "Sorry, I didn't catch an answer. Try again." }.toString()
            }
            history += MessageParam.ofUserOfContentBlockParams(calls.map { ContentBlockParam.ofToolResult(run(it)) })
        }
        trimHistory()
        return spoken.ifBlank { "That took too many steps. Try asking again more simply." }.toString()
    }

    private fun run(call: ToolUseBlock): ToolResultBlockParam {
        val input = call._input().asObject().orElse(emptyMap())
        fun arg(name: String) = input[name]?.asString()?.orElse(null)?.trim().orEmpty()
        fun number(name: String): Int? = input[name]?.asNumber()?.orElse(null)?.toInt() ?: arg(name).filter { it.isDigit() }.toIntOrNull()
        fun flag(name: String): Boolean = input[name]?.asBoolean()?.orElse(null) ?: arg(name).equals("true", ignoreCase = true)
        val (text, error) = runCatching {
            when (call.name()) {
                START_TASK -> actions.startTask(arg("repo"), arg("request"))
                REPLY_TO_TASK -> actions.replyToTask(arg("task_id"), arg("text"))
                ADD_REPO -> actions.addRepo(arg("name"))
                GET_STATUS -> actions.status(arg("repo"), number("pr_number"), arg("task_id"))
                STOP_TASK -> actions.stopTask(arg("task_id"))
                MERGE -> merges.handle(arg("repo"), number("pr_number"), flag("confirmed"), turn)
                else -> throw IllegalArgumentException("Unknown tool ${call.name()}")
            }
        }.fold({ it to false }, { (it.message ?: "That didn't work.") to true })
        return ToolResultBlockParam.builder().toolUseId(call.id()).content(text).isError(error).build()
    }

    private fun params(chat: ChatProfile): MessageCreateParams = MessageCreateParams.builder()
        .model(chat.model.id)
        .maxTokens(MAX_TOKENS)
        // Low effort by default keeps replies quick, which matters more than depth while driving.
        .outputConfig(OutputConfig.builder().effort(effort(chat.effort)).build())
        .system(system())
        .addTool(startTaskTool)
        .addTool(replyTool)
        .addTool(addRepoTool)
        .addTool(statusTool)
        .addTool(stopTool)
        .addTool(mergeTool)
        .messages(history.toList())
        .build()

    private fun system(): String {
        val earlier = runCatching { ChatMemory.describe(memory().recent(), startedMillis, clock()) }.getOrNull()
        return SYSTEM + "\n\nCurrent state of the app:\n" + actions.context() +
            (earlier?.let { "\n\n$MEMORY_INTRO\n$it" } ?: "")
    }

    /** Keeps the conversation short. Only cuts right before a plain spoken question, so tool calls stay paired. */
    private fun trimHistory() {
        if (history.size <= MAX_HISTORY) return
        val cut = (history.size - MAX_HISTORY until history.size).firstOrNull { i ->
            history[i].role() == MessageParam.Role.USER && history[i].content().isString()
        } ?: return
        repeat(cut) { history.removeAt(0) }
    }

    private fun clientFor(key: String): AnthropicClient {
        val current = client
        if (current != null && clientKey == key) return current
        current?.close()
        return clientFactory(key).also { client = it; clientKey = key }
    }

    companion object {
        private const val MAX_TOKENS = 4000L
        private const val MAX_TOOL_ROUNDS = 4
        private const val MAX_HISTORY = 30
        private const val START_TASK = "start_coding_task"
        private const val REPLY_TO_TASK = "reply_to_task"
        private const val ADD_REPO = "add_repo"
        private const val GET_STATUS = "get_status"
        private const val STOP_TASK = "stop_task"
        private const val MERGE = "merge_pull_request"

        private const val MEMORY_INTRO =
            "Your earlier conversations with the driver, from past drives, oldest first. Use them when the driver " +
                "refers back to something, but don't bring them up unprompted:"

        private val SYSTEM = """
            You are Claude, talking with the driver through their car's speakers. Everything you write is read
            aloud by text to speech, so answer in a few short spoken sentences: no markdown, lists, code, URLs,
            or emoji. Keep it brief and easy to follow while driving, and never encourage them to look at a screen.
            Use Canadian spelling, such as colour, centre, cancelled and organize.
            You can also start coding tasks in the driver's GitHub repos and reply to running tasks with the tools.
            Only start a task when the driver clearly asks for one, and say back in one sentence what you started.
            When the driver names one of the task templates, start a task with that template's request as written,
            adding anything extra they said.
            If the repo is unclear, ask which one, naming at most three.
            When the driver asks to add a repo, call add_repo with the name as they said it, then say which repo
            was added. If it didn't work, read back the reason briefly.
            When the driver asks how something is going, such as a task, a pull request's checks or a repo, call
            get_status and sum up the answer in a sentence or two. When they ask to stop a task, call stop_task.
            To merge a pull request, call merge_pull_request without confirmed first, read back what it says and
            ask the driver to confirm. Only call it again with confirmed true after the driver says yes. Never
            merge on your own initiative.
        """.trimIndent()

        private val startTaskTool = Tool.builder()
            .name(START_TASK)
            .description("Start a coding task: an agent works on the repo, pushes a branch and opens a pull request.")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("repo", stringProp("The repo as owner/name, from the saved repos list."))
                            .putAdditionalProperty("request", stringProp("What to do, in the driver's words, made clear and complete."))
                            .build(),
                    )
                    .required(listOf("repo", "request"))
                    .build(),
            )
            .build()

        private val replyTool = Tool.builder()
            .name(REPLY_TO_TASK)
            .description("Send a message to a task that already exists, such as an answer to its question.")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("task_id", stringProp("The task id from the recent tasks list."))
                            .putAdditionalProperty("text", stringProp("The message for the task."))
                            .build(),
                    )
                    .required(listOf("task_id", "text"))
                    .build(),
            )
            .build()

        private val addRepoTool = Tool.builder()
            .name(ADD_REPO)
            .description("Save one of the driver's GitHub repos so tasks can use it. Finds it among the repos their token can push to.")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("name", stringProp("The repo as the driver said it: owner/name, or just the name."))
                            .build(),
                    )
                    .required(listOf("name"))
                    .build(),
            )
            .build()

        private val statusTool = Tool.builder()
            .name(GET_STATUS)
            .description(
                "Check where things stand: a task, a pull request and its checks, or a repo's main branch and open pull requests. " +
                    "With no arguments, lists the tasks that are still going.",
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("task_id", stringProp("A task id from the recent tasks list, if the driver means a task."))
                            .putAdditionalProperty("repo", stringProp("The repo as owner/name or as the driver said it, if they named one."))
                            .putAdditionalProperty("pr_number", intProp("The pull request number, if the driver said one."))
                            .build(),
                    )
                    .build(),
            )
            .build()

        private val stopTool = Tool.builder()
            .name(STOP_TASK)
            .description("Stop a task Claude is working on. With no task id, stops the only running task.")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("task_id", stringProp("The task id from the recent tasks list."))
                            .build(),
                    )
                    .build(),
            )
            .build()

        private val mergeTool = Tool.builder()
            .name(MERGE)
            .description(
                "Merge a pull request. Call it first without confirmed to check it and get a question for the driver. " +
                    "Call it again with confirmed true only after the driver said yes to that question.",
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("repo", stringProp("The repo as owner/name or as the driver said it. Leave out if unclear."))
                            .putAdditionalProperty("pr_number", intProp("The pull request number."))
                            .putAdditionalProperty("confirmed", JsonValue.from(mapOf("type" to "boolean", "description" to "True only once the driver said yes.")))
                            .build(),
                    )
                    .build(),
            )
            .build()

        private fun intProp(description: String) = JsonValue.from(mapOf("type" to "integer", "description" to description))

        private fun stringProp(description: String) = JsonValue.from(mapOf("type" to "string", "description" to description))

        private fun defaultClient(key: String): AnthropicClient = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .timeout(Duration.ofSeconds(60))
            .build()

        fun costUsd(message: Message, model: ModelChoice = ModelChoice.Opus): Double {
            val usage = message.usage()
            val input = usage.inputTokens() + usage.cacheCreationInputTokens().orElse(0L) + usage.cacheReadInputTokens().orElse(0L)
            return model.costUsd(input, usage.outputTokens())
        }

        private fun effort(effort: EffortChoice): OutputConfig.Effort = when (effort) {
            EffortChoice.Low -> OutputConfig.Effort.LOW
            EffortChoice.Medium -> OutputConfig.Effort.MEDIUM
            EffortChoice.High -> OutputConfig.Effort.HIGH
            EffortChoice.XHigh -> OutputConfig.Effort.XHIGH
            EffortChoice.Max -> OutputConfig.Effort.MAX
        }

        private fun StringBuilder.appendSentence(text: String) {
            val clean = text.trim()
            if (clean.isEmpty()) return
            if (isNotEmpty()) append(' ')
            append(clean)
        }
    }
}

/**
 * Merging by voice takes two tool calls in different turns: the first describes the pull request and asks the driver,
 * and only a call after the driver spoke again, for the same pull request, merges it. A misheard "merge" or an
 * eager model can't merge anything on its own.
 */
internal class MergeConfirmation(private val actions: AssistantActions) {
    /** A merge described to the driver, and the turn it was described in. */
    private var pending: Pair<MergePreview, Int>? = null

    fun clear() { pending = null }

    fun handle(repo: String, prNumber: Int?, confirmed: Boolean, turn: Int): String {
        val waiting = pending
        if (confirmed && waiting != null && waiting.second < turn && sameTarget(waiting.first, repo, prNumber)) {
            pending = null
            return actions.merge(waiting.first.repo, waiting.first.number)
        }
        val preview = actions.previewMerge(repo, prNumber)
        if (!preview.ready) {
            pending = null
            return "${preview.summary} It can't merge right now. Tell the driver why in a sentence."
        }
        pending = preview to turn
        return "${preview.summary} Not merged yet. Ask the driver to confirm merging pull request ${preview.number} in ${preview.repo.name}, " +
            "and only after they say yes, call merge_pull_request again with confirmed true."
    }

    private fun sameTarget(preview: MergePreview, repo: String, prNumber: Int?): Boolean =
        (prNumber == null || prNumber == preview.number) &&
            (repo.isBlank() || repo.equals(preview.repo.fullName, ignoreCase = true) || RepoRef.matchSpoken(repo, listOf(preview.repo)).isNotEmpty())
}
