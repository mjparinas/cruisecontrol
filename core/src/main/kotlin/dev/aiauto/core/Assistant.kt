package dev.aiauto.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
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

    /** Checks whether a pull request can close or reopen, without changing it. */
    fun previewClose(repo: String, prNumber: Int?, reopen: Boolean): ClosePreview =
        throw UnsupportedOperationException("Closing isn't available.")

    /** Closes a pull request the driver confirmed. Returns what to tell Claude. */
    fun close(repo: RepoRef, number: Int): String = throw UnsupportedOperationException("Closing isn't available.")

    /** Reopens a pull request the driver confirmed. Returns what to tell Claude. */
    fun reopen(repo: RepoRef, number: Int): String = throw UnsupportedOperationException("Closing isn't available.")
}

/**
 * A spoken conversation for the car's mic button. Each [ask] takes what the driver said and returns the answer,
 * ready to be read aloud. Claude answers by default; Settings can pick an OpenAI or Gemini model instead. Whichever
 * answers can also start a coding task or reply to one through [AssistantActions]. A question that fails before any
 * tool runs, from no signal, a timeout, a rate limit or a brief server error, is kept and answered by [sendQueued]
 * once the phone can get through. Blocking: call it off the main thread.
 */
class Assistant(
    private val apiKey: (ChatProvider) -> String?,
    private val actions: AssistantActions,
    private val canSpend: () -> Boolean = { true },
    private val onSpend: (Double) -> Unit = {},
    private val profile: () -> ChatProfile = { ChatProfile() },
    private val memory: () -> ChatMemory = { NoChatMemory },
    private val clock: () -> Long = System::currentTimeMillis,
    private val clientFactory: (String) -> AnthropicClient = ::defaultClient,
    private val http: ChatHttp = OkHttpChatHttp(),
) {
    /** Exchanges remembered before this start are from earlier drives, so they go in the system prompt. */
    private var startedMillis = clock()
    /** Counts the driver's questions, so a merge can only be confirmed by a later one. */
    private var turn = 0
    private val merges = MergeConfirmation(actions)
    private val closes = CloseConfirmation(actions)
    private var conversation: ChatConversation? = null
    /** The provider and key [conversation] was made for. A new model provider or key starts a new one. */
    private var conversationFor: Pair<ChatProvider, String>? = null
    /** Questions asked with no signal, oldest first, with when they were asked. */
    private val queued = ArrayDeque<Pair<String, Long>>()
    /** True once a tool ran in the current turn, so a lost connection can't make the turn run twice. */
    private var acted = false

    @Synchronized
    fun ask(spoken: String): String {
        val (reply, retryable) = attempt(spoken)
        if (!retryable) return reply
        if (queued.size >= MAX_QUEUED) return "I couldn't get through, and I'm already holding $MAX_QUEUED questions. Try again in a minute."
        queued.addLast(spoken to clock())
        return QUEUED
    }

    /** True while questions that couldn't get through are waiting for [sendQueued]. */
    @Synchronized
    fun hasQueued(): Boolean = queued.isNotEmpty()

    /**
     * Asks the questions kept while there was no signal or the chat provider was briefly unavailable, oldest first,
     * and returns each answer ready to be read aloud. Call it when the network comes back. Questions older than
     * [QUEUE_MAX_AGE_MILLIS] are dropped, since the driver has likely moved on. If the connection drops again, the
     * rest stay queued for next time.
     */
    @Synchronized
    fun sendQueued(): List<String> {
        val answers = mutableListOf<String>()
        while (queued.isNotEmpty()) {
            val (spoken, askedMillis) = queued.first()
            if (clock() - askedMillis > QUEUE_MAX_AGE_MILLIS) {
                queued.removeFirst()
                continue
            }
            val (reply, retryable) = attempt(spoken)
            if (retryable) break
            queued.removeFirst()
            answers += "You asked: ${spoken.trim().trimEnd('.', '?', '!')}. $reply"
        }
        return answers
    }

    /**
     * One turn. The flag is true when it failed before anything was done for a reason that's worth retrying
     * (no signal, a timeout, a rate limit, or a brief server error), so it can be held and tried again.
     */
    private fun attempt(spoken: String): Pair<String, Boolean> {
        val chat = profile()
        val provider = chat.model.provider
        val key = apiKey(provider) ?: return "Add your ${provider.label} API key in the app on your phone first." to false
        if (!canSpend()) return "You've reached your daily or monthly spending limit, so I can't answer right now. You can raise it in Settings on your phone." to false
        var talk: ChatConversation? = null
        var start = 0
        acted = false
        return try {
            talk = conversationFor(provider, key)
            start = talk.size
            turn++
            talk.addUser(spoken)
            converse(talk, chat).also { reply ->
                runCatching { memory().remember(ChatExchange(spoken, reply, clock())) }
            } to false
        } catch (e: Throwable) {
            // Drop the half-finished turn so the next question starts from a clean history.
            talk?.rollback(start)
            Failures.describe(e) to (!acted && Failures.isTransient(e))
        }
    }

    /** Forgets the conversation, e.g. when the driver starts over. */
    @Synchronized
    fun reset() {
        conversation?.rollback(0)
        startedMillis = clock()
        merges.clear()
        closes.clear()
    }

    private fun converse(talk: ChatConversation, chat: ChatProfile): String {
        val spoken = StringBuilder()
        repeat(MAX_TOOL_ROUNDS) {
            val reply = talk.send(system(chat.model.provider), TOOLS, chat)
            onSpend(reply.costUsd)
            reply.text.forEach { spoken.appendSentence(it) }
            if (reply.refused) return spoken.ifBlank { "I can't help with that one." }.toString()
            if (reply.calls.isEmpty()) {
                talk.trim(MAX_HISTORY)
                return spoken.ifBlank { "Sorry, I didn't catch an answer. Try again." }.toString()
            }
            talk.addResults(reply.calls.map(::run))
        }
        talk.trim(MAX_HISTORY)
        return spoken.ifBlank { "That took too many steps. Try asking again more simply." }.toString()
    }

    private fun run(call: ToolCall): ToolResult {
        fun arg(name: String) = call.args[name]?.trim().orEmpty()
        fun number(name: String): Int? = arg(name).let { it.toIntOrNull() ?: it.filter { c -> c.isDigit() }.toIntOrNull() }
        fun flag(name: String): Boolean = arg(name).equals("true", ignoreCase = true)
        acted = true
        val (text, error) = runCatching {
            when (call.name) {
                START_TASK -> actions.startTask(arg("repo"), arg("request"))
                REPLY_TO_TASK -> actions.replyToTask(arg("task_id"), arg("text"))
                ADD_REPO -> actions.addRepo(arg("name"))
                GET_STATUS -> actions.status(arg("repo"), number("pr_number"), arg("task_id"))
                STOP_TASK -> actions.stopTask(arg("task_id"))
                MERGE -> merges.handle(arg("repo"), number("pr_number"), flag("confirmed"), turn)
                CLOSE -> closes.handle(arg("repo"), number("pr_number"), flag("confirmed"), flag("reopen"), turn)
                else -> throw IllegalArgumentException("Unknown tool ${call.name}")
            }
        }.fold({ it to false }, { (it.message ?: "That didn't work.") to true })
        return ToolResult(call.id, text, error)
    }

    private fun system(provider: ChatProvider): String {
        val earlier = runCatching { ChatMemory.describe(memory().recent(), startedMillis, clock()) }.getOrNull()
        return system(provider.assistantName) + "\n\nCurrent state of the app:\n" + actions.context() +
            (earlier?.let { "\n\n$MEMORY_INTRO\n$it" } ?: "")
    }

    private fun conversationFor(provider: ChatProvider, key: String): ChatConversation {
        val current = conversation
        if (current != null && conversationFor == provider to key) return current
        current?.close()
        val made = when (provider) {
            ChatProvider.Anthropic -> AnthropicConversation(clientFactory(key))
            ChatProvider.OpenAI -> OpenAiConversation(key, http)
            ChatProvider.Gemini -> GeminiConversation(key, http)
        }
        conversation = made
        conversationFor = provider to key
        return made
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 4
        private const val MAX_HISTORY = 30
        /** Questions kept with no signal at most, so a long dead zone can't pile up a stack of answers. */
        const val MAX_QUEUED = 5
        /** How long a question asked with no signal stays worth answering. */
        const val QUEUE_MAX_AGE_MILLIS = 30 * 60_000L
        const val QUEUED = "I couldn't get through right now. I'll send that once the connection is steadier."
        private const val START_TASK = "start_coding_task"
        private const val REPLY_TO_TASK = "reply_to_task"
        private const val ADD_REPO = "add_repo"
        private const val GET_STATUS = "get_status"
        private const val STOP_TASK = "stop_task"
        private const val MERGE = "merge_pull_request"
        private const val CLOSE = "close_pull_request"

        private const val MEMORY_INTRO =
            "Your earlier conversations with the driver, from past drives, oldest first. Use them when the driver " +
                "refers back to something, but don't bring them up unprompted:"

        private fun system(name: String) = """
            You are $name, talking with the driver through their car's speakers. Everything you write is read
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
            To close a pull request without merging, call close_pull_request without confirmed first, read back what
            it says and ask the driver to confirm. Only call it again with confirmed true after they say yes. To
            reopen one that was closed without merging, call close_pull_request with reopen true the same way.
            Never close or reopen on your own initiative.
        """.trimIndent()

        private val TOOLS = listOf(
            ChatTool(
                START_TASK,
                "Start a coding task: an agent works on the repo, pushes a branch and opens a pull request.",
                listOf(
                    Triple("repo", "string", "The repo as owner/name, from the saved repos list."),
                    Triple("request", "string", "What to do, in the driver's words, made clear and complete."),
                ),
                required = listOf("repo", "request"),
            ),
            ChatTool(
                REPLY_TO_TASK,
                "Send a message to a task that already exists, such as an answer to its question.",
                listOf(
                    Triple("task_id", "string", "The task id from the recent tasks list."),
                    Triple("text", "string", "The message for the task."),
                ),
                required = listOf("task_id", "text"),
            ),
            ChatTool(
                ADD_REPO,
                "Save one of the driver's GitHub repos so tasks can use it. Finds it among the repos their token can push to.",
                listOf(Triple("name", "string", "The repo as the driver said it: owner/name, or just the name.")),
                required = listOf("name"),
            ),
            ChatTool(
                GET_STATUS,
                "Check where things stand: a task, a pull request and its checks, or a repo's main branch and open pull requests. " +
                    "With no arguments, lists the tasks that are still going.",
                listOf(
                    Triple("task_id", "string", "A task id from the recent tasks list, if the driver means a task."),
                    Triple("repo", "string", "The repo as owner/name or as the driver said it, if they named one."),
                    Triple("pr_number", "integer", "The pull request number, if the driver said one."),
                ),
            ),
            ChatTool(
                STOP_TASK,
                "Stop a task Claude is working on. With no task id, stops the only running task.",
                listOf(Triple("task_id", "string", "The task id from the recent tasks list.")),
            ),
            ChatTool(
                MERGE,
                "Merge a pull request. Call it first without confirmed to check it and get a question for the driver. " +
                    "Call it again with confirmed true only after the driver said yes to that question.",
                listOf(
                    Triple("repo", "string", "The repo as owner/name or as the driver said it. Leave out if unclear."),
                    Triple("pr_number", "integer", "The pull request number."),
                    Triple("confirmed", "boolean", "True only once the driver said yes."),
                ),
            ),
            ChatTool(
                CLOSE,
                "Close a pull request without merging, or reopen one that was closed without merging. Call it first " +
                    "without confirmed to check it and get a question for the driver. Call it again with confirmed " +
                    "true only after the driver said yes. Set reopen true only when reopening.",
                listOf(
                    Triple("repo", "string", "The repo as owner/name or as the driver said it. Leave out if unclear."),
                    Triple("pr_number", "integer", "The pull request number."),
                    Triple("confirmed", "boolean", "True only once the driver said yes."),
                    Triple("reopen", "boolean", "True to reopen a closed pull request instead of closing an open one."),
                ),
            ),
        )

        private fun defaultClient(key: String): AnthropicClient = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .timeout(Duration.ofSeconds(60))
            .build()

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

/**
 * Closing or reopening by voice takes two tool calls in different turns, the same way merging does: the first
 * describes the pull request and asks, and only a later yes for the same pull request changes it.
 */
internal class CloseConfirmation(private val actions: AssistantActions) {
    private var pending: Pair<ClosePreview, Int>? = null

    fun clear() { pending = null }

    fun handle(repo: String, prNumber: Int?, confirmed: Boolean, reopen: Boolean, turn: Int): String {
        val waiting = pending
        if (confirmed && waiting != null && waiting.second < turn && waiting.first.reopen == reopen &&
            sameTarget(waiting.first, repo, prNumber)
        ) {
            pending = null
            return if (waiting.first.reopen) {
                actions.reopen(waiting.first.repo, waiting.first.number)
            } else {
                actions.close(waiting.first.repo, waiting.first.number)
            }
        }
        val preview = actions.previewClose(repo, prNumber, reopen)
        if (!preview.ready) {
            pending = null
            val verb = if (reopen) "reopen" else "close"
            return "${preview.summary} It can't $verb right now. Tell the driver why in a sentence."
        }
        pending = preview to turn
        val verb = if (reopen) "reopening" else "closing"
        val toolHint = if (reopen) {
            "call close_pull_request again with confirmed true and reopen true"
        } else {
            "call close_pull_request again with confirmed true"
        }
        return "${preview.summary} Not changed yet. Ask the driver to confirm $verb pull request ${preview.number} in " +
            "${preview.repo.name}, and only after they say yes, $toolHint."
    }

    private fun sameTarget(preview: ClosePreview, repo: String, prNumber: Int?): Boolean =
        (prNumber == null || prNumber == preview.number) &&
            (repo.isBlank() || repo.equals(preview.repo.fullName, ignoreCase = true) || RepoRef.matchSpoken(repo, listOf(preview.repo)).isNotEmpty())
}
