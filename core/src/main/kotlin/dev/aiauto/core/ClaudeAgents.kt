package dev.aiauto.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.agents.AgentCreateParams
import com.anthropic.models.beta.agents.BetaManagedAgentsAgentToolset20260401Params
import com.anthropic.models.beta.agents.BetaManagedAgentsAgentToolsetDefaultConfigParams
import com.anthropic.models.beta.agents.BetaManagedAgentsAutoPolicy
import com.anthropic.models.beta.agents.BetaManagedAgentsModelConfigParams
import com.anthropic.models.beta.agents.BetaManagedAgentsWebFetchToolConfigParams
import com.anthropic.models.beta.agents.BetaManagedAgentsWebSearchToolConfigParams
import com.anthropic.models.beta.environments.BetaCloudConfigParams
import com.anthropic.models.beta.environments.BetaLimitedNetworkParams
import com.anthropic.models.beta.environments.BetaUnrestrictedNetwork
import com.anthropic.models.beta.environments.EnvironmentCreateParams
import com.anthropic.models.beta.sessions.BetaManagedAgentsAgentParams
import com.anthropic.models.beta.sessions.BetaManagedAgentsGitHubRepositoryResourceParams
import com.anthropic.models.beta.sessions.SessionCreateParams
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsAgentEvaluatedPermission
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsStreamSessionEvents
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserMessageEventParams
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserToolConfirmationEventParams
import com.anthropic.models.beta.sessions.events.EventSendParams
import com.anthropic.core.Timeout
import com.anthropic.models.beta.BetaCurrency
import com.anthropic.models.beta.BetaMonetaryAmount
import com.anthropic.models.beta.sessions.SessionUpdateParams
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserInterruptEventParams
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsSessionEvent
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsSessionStatusIdleEvent
import java.time.Duration
import java.util.Optional
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What a running session reports back to the app. */
sealed interface AgentUpdate {
    /** A short progress note, e.g. "Editing files". */
    data class Progress(val text: String) : AgentUpdate

    /** Something Claude said, already cleaned for display and speech. */
    data class Said(val text: String) : AgentUpdate

    /** Claude finished its turn and is waiting for the next prompt. */
    data object TurnDone : AgentUpdate

    /** A tool call needed approval; it was declined because nobody can review it while driving. */
    data class Declined(val toolName: String) : AgentUpdate

    /** A tool call needs approval and the car is parked, so it waits for [AgentClient.confirm]. */
    data class NeedsApproval(val toolUseId: String, val toolName: String, val detail: String?) : AgentUpdate

    data class Failed(val reason: String) : AgentUpdate

    /** The session's running total cost so far, in US dollars at list price. */
    data class Cost(val totalUsd: Double) : AgentUpdate

    /** The session stopped at the spending cap the app set for it. */
    data object BudgetReached : AgentUpdate
}

/** What [TaskEngine] needs from Claude. [ClaudeAgents] is the real one; tests use a fake. */
interface AgentClient : AutoCloseable {
    /**
     * Makes an agent with [profile]. Reuses the environments in [existing] when there are any, so changing a setting
     * only makes a new agent.
     */
    fun createSetup(profile: AgentProfile = AgentProfile(), existing: AgentSetup? = null): AgentSetup
    fun createSession(setup: AgentSetup, task: Task, githubToken: String): String
    fun sendPrompt(sessionId: String, prompt: String)
    fun follow(
        sessionId: String,
        seenEventIds: MutableSet<String>,
        onConnected: () -> Unit = {},
        shouldStop: () -> Boolean = { false },
        /** True while the driver can review flagged steps (the car is parked). Otherwise they're declined. */
        canReview: () -> Boolean = { false },
        onUpdate: (AgentUpdate) -> Unit,
    ): Boolean

    /** Lists the conversation kept by the hosted session, including history trimmed from a synced task copy. */
    fun history(sessionId: String): List<TaskMessage> = emptyList()

    /** Answers a step reported as [AgentUpdate.NeedsApproval]. */
    fun confirm(sessionId: String, toolUseId: String, allow: Boolean) {}

    /** Asks Claude to stop what it's doing. The session stays and can take a new prompt. */
    fun interrupt(sessionId: String) {}

    /** Archives a session the user cleared, so it stops showing up as active. */
    fun archive(sessionId: String) {}

    /** Caps what the whole session may cost, so Claude stops on its side when the daily limit runs out. */
    fun setBudget(sessionId: String, maxUsd: Double) {}

    /** The session's total cost so far, or null when it isn't reported. */
    fun costUsd(sessionId: String): Double? = null
}

/**
 * Thin wrapper over Claude Managed Agents: one agent + environment created once,
 * then one session per task with the user's GitHub repo mounted.
 */
class ClaudeAgents(apiKey: String) : AgentClient {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        // The SDK retries dropped connections, 429s and 5xx on ordinary calls. Streams are reconnected by TaskEngine.
        .maxRetries(3)
        .timeout(
            Timeout.builder()
                .connect(Duration.ofSeconds(15))
                // A stream that stays silent this long is treated as dead and reconnected.
                .read(Duration.ofMinutes(3))
                .request(Duration.ofMinutes(10))
                .build(),
        )
        .build()

    /** Creates the agent, and the environments it needs that [existing] doesn't have yet. Store the result. */
    override fun createSetup(profile: AgentProfile, existing: AgentSetup?): AgentSetup {
        val environmentId = existing?.environmentId ?: createEnvironment(web = false)
        val webEnvironmentId = existing?.webEnvironmentId ?: if (profile.webSearch) createEnvironment(web = true) else null
        val agent = client.beta().agents().create(
            AgentCreateParams.builder()
                .name("Cruise Control coding agent")
                .model(
                    BetaManagedAgentsModelConfigParams.builder()
                        .id(profile.modelChoice.id)
                        .effort(effortLevel(profile.effortChoice))
                        .build(),
                )
                .system(profile.systemPrompt(SYSTEM_PROMPT))
                .addTool(
                    BetaManagedAgentsAgentToolset20260401Params.builder()
                        .defaultConfig(
                            BetaManagedAgentsAgentToolsetDefaultConfigParams.builder()
                                .permissionPolicy(BetaManagedAgentsAutoPolicy.builder().build())
                                .build(),
                        )
                        .addConfig(BetaManagedAgentsWebFetchToolConfigParams.builder().enabled(profile.webSearch).build())
                        .addConfig(BetaManagedAgentsWebSearchToolConfigParams.builder().enabled(profile.webSearch).build())
                        .build(),
                )
                .build(),
        )
        return AgentSetup(agent.id(), agent.version(), environmentId, profile, webEnvironmentId)
    }

    /**
     * The normal environment only reaches package registries. Web search needs open internet access, because with
     * limited networking the web tools can only reach the hosts the environment allows.
     */
    private fun createEnvironment(web: Boolean): String {
        val config = BetaCloudConfigParams.builder()
        if (web) {
            config.networking(BetaUnrestrictedNetwork.builder().build())
        } else {
            config.networking(
                BetaLimitedNetworkParams.builder()
                    .allowPackageManagers(true)
                    .allowMcpServers(false)
                    .build(),
            )
        }
        return client.beta().environments().create(
            EnvironmentCreateParams.builder()
                .name(if (web) "aiauto-env-web" else "aiauto-env")
                .config(config.build())
                .build(),
        ).id()
    }

    private fun effortLevel(effort: EffortChoice) = when (effort) {
        EffortChoice.Low -> BetaManagedAgentsModelConfigParams.Effort.BetaManagedAgentsEffortLevel.LOW
        EffortChoice.Medium -> BetaManagedAgentsModelConfigParams.Effort.BetaManagedAgentsEffortLevel.MEDIUM
        EffortChoice.High -> BetaManagedAgentsModelConfigParams.Effort.BetaManagedAgentsEffortLevel.HIGH
        EffortChoice.XHigh -> BetaManagedAgentsModelConfigParams.Effort.BetaManagedAgentsEffortLevel.XHIGH
        EffortChoice.Max -> BetaManagedAgentsModelConfigParams.Effort.BetaManagedAgentsEffortLevel.MAX
    }

    /** Creates a session for [task] with the repo cloned at [REPO_PATH]. Returns the session id. */
    override fun createSession(setup: AgentSetup, task: Task, githubToken: String): String {
        val session = client.beta().sessions().create(
            SessionCreateParams.builder()
                .agent(
                    BetaManagedAgentsAgentParams.builder()
                        .id(setup.agentId)
                        .version(setup.agentVersion)
                        .build(),
                )
                .environmentId(setup.sessionEnvironmentId)
                .title("${task.repo.fullName}: ${task.title}")
                .addResource(
                    BetaManagedAgentsGitHubRepositoryResourceParams.builder()
                        .url(task.repo.url)
                        .mountPath(REPO_PATH)
                        .authorizationToken(githubToken)
                        .build(),
                )
                .build(),
        )
        return session.id()
    }

    /** Sends a spoken or typed prompt. The first prompt of a task carries the branch to use. */
    override fun sendPrompt(sessionId: String, prompt: String) {
        client.beta().sessions().events().send(
            sessionId,
            EventSendParams.builder()
                .addEvent(
                    BetaManagedAgentsUserMessageEventParams.builder()
                        .addTextContent(prompt)
                        .build(),
                )
                .build(),
        )
    }

    /**
     * Streams the session until Claude finishes its turn, fails, or [shouldStop] returns true.
     * Opens the stream first, then lists past events so nothing is reported twice after a reconnect.
     * If the turn already ended while the app wasn't listening (the phone lost signal, or the app was
     * closed), that outcome is reported straight from the history instead of waiting forever.
     *
     * Returns true when the turn is over, false when the server closed the stream mid-turn and the
     * caller should reconnect. [onConnected] runs once the stream and history are both loaded.
     */
    override fun follow(
        sessionId: String,
        seenEventIds: MutableSet<String>,
        onConnected: () -> Unit,
        shouldStop: () -> Boolean,
        canReview: () -> Boolean,
        onUpdate: (AgentUpdate) -> Unit,
    ): Boolean {
        client.beta().sessions().events().streamStreaming(sessionId).use { stream ->
            var lastUserMessage = -1
            var lastStop: Pair<Int, BetaManagedAgentsSessionEvent>? = null
            val confirmed = HashSet<String>()
            val calls = HashMap<String, Pair<String, String?>>()
            var lastCost: Double? = null
            // IDs we already reported before this reconnect. History marks everything as seen so the live
            // stream doesn't double-fire; without this copy, messages that arrived while we were offline
            // would be marked seen and then skipped forever.
            val previouslySeen = seenEventIds.toSet()
            val missedSaid = mutableListOf<String>()
            for ((index, past) in client.beta().sessions().events().list(sessionId).autoPager().withIndex()) {
                val id = eventId(past._json())
                val fresh = id != null && id !in previouslySeen
                if (id != null) seenEventIds.add(id)
                when {
                    past.isSessionUsage() -> costOf(past.asSessionUsage().usage().listCost())?.let { lastCost = it }
                    past.isUserMessage() -> lastUserMessage = index
                    past.isUserToolConfirmation() -> confirmed.add(past.asUserToolConfirmation().toolUseId())
                    past.isAgentToolUse() -> past.asAgentToolUse().let { calls[it.id()] = it.name() to detailOf(it.input()._additionalProperties()) }
                    past.isAgentMcpToolUse() -> past.asAgentMcpToolUse().let { calls[it.id()] = it.name() to null }
                    past.isAgentMessage() -> {
                        if (fresh && index > lastUserMessage) {
                            val cleaned = SpokenStatus.clean(agentMessageText(past.asAgentMessage()))
                            if (cleaned.isNotBlank()) missedSaid += cleaned
                        }
                    }
                    past.isSessionStatusIdle() || past.isSessionStatusTerminated() -> lastStop = index to past
                    past.isSessionStatusRunning() || past.isSessionStatusRescheduled() -> lastStop = null
                }
            }
            onConnected()
            lastCost?.let { onUpdate(AgentUpdate.Cost(it)) }
            // Speak anything Claude said while we weren't listening, before catch-up or the live stream.
            missedSaid.forEach { onUpdate(AgentUpdate.Said(it)) }
            val stop = lastStop
            if (stop != null && stop.first > lastUserMessage && catchUp(sessionId, stop.second, confirmed, calls, canReview, onUpdate)) return true
            for (event in stream.stream()) {
                if (shouldStop()) return true
                val id = eventId(event._json())
                if (id != null && !seenEventIds.add(id)) continue
                if (handle(sessionId, event, canReview, onUpdate)) return true
            }
        }
        return false
    }

    override fun history(sessionId: String): List<TaskMessage> {
        var firstUserMessage = true
        return client.beta().sessions().events().list(sessionId).autoPager().withIndex().mapNotNull { (index, event) ->
            val id = eventId(event._json()) ?: "session-event-$index"
            val time = eventMillis(event._json())
            when {
                event.isUserMessage() -> {
                    if (firstUserMessage) {
                        firstUserMessage = false
                        null
                    } else {
                        val text = eventText(event._json())
                        TaskMessage(id, Speaker.You, text, time).takeIf { text.isNotBlank() }
                    }
                }
                event.isAgentMessage() -> {
                    val text = SpokenStatus.clean(agentMessageText(event.asAgentMessage()))
                    TaskMessage(id, Speaker.Claude, text, time).takeIf { text.isNotBlank() }
                }
                event.isAgentToolUse() -> {
                    val call = event.asAgentToolUse()
                    val input = call.input()._additionalProperties()
                    val command = input["command"]?.asString()?.orElse(null)
                    SpokenStatus.forToolUse(call.name(), command)?.let { TaskMessage(id, Speaker.Status, it, time) }
                }
                else -> null
            }
        }.toList()
    }

    /** Reports a stop that happened before we connected. Returns true when the turn is over. */
    private fun catchUp(
        sessionId: String,
        event: BetaManagedAgentsSessionEvent,
        confirmed: Set<String>,
        calls: Map<String, Pair<String, String?>>,
        canReview: () -> Boolean,
        onUpdate: (AgentUpdate) -> Unit,
    ): Boolean {
        if (event.isSessionStatusTerminated()) {
            onUpdate(AgentUpdate.Failed("The session ended."))
            return true
        }
        val reason = event.asSessionStatusIdle().stopReason()
        if (reason.isRequiresAction()) {
            // Claude was waiting on a tool approval when we lost the connection. Ask again, or decline while driving.
            reason.asRequiresAction().eventIds().filter { it !in confirmed }.forEach { id ->
                val (name, detail) = calls[id] ?: ("tool" to null)
                askOrDecline(sessionId, id, name, detail, canReview, onUpdate)
            }
            return false
        }
        return idleOutcome(reason, onUpdate)
    }

    /** Returns true when the turn is over. */
    private fun handle(
        sessionId: String,
        event: BetaManagedAgentsStreamSessionEvents,
        canReview: () -> Boolean,
        onUpdate: (AgentUpdate) -> Unit,
    ): Boolean {
        when {
            event.isAgentMessage() -> {
                val cleaned = SpokenStatus.clean(agentMessageText(event.asAgentMessage()))
                if (cleaned.isNotBlank()) onUpdate(AgentUpdate.Said(cleaned))
            }
            event.isAgentToolUse() -> {
                val call = event.asAgentToolUse()
                val input = call.input()._additionalProperties()
                if (call.evaluatedPermission() == ASK) {
                    askOrDecline(sessionId, call.id(), call.name(), detailOf(input), canReview, onUpdate)
                } else {
                    val command = input["command"]?.asString()?.orElse(null)
                    SpokenStatus.forToolUse(call.name(), command)?.let { onUpdate(AgentUpdate.Progress(it)) }
                }
            }
            event.isAgentMcpToolUse() -> {
                val call = event.asAgentMcpToolUse()
                if (call.evaluatedPermission() == ASK) askOrDecline(sessionId, call.id(), call.name(), null, canReview, onUpdate)
            }
            event.isSessionStatusIdle() -> {
                val reason = event.asSessionStatusIdle().stopReason()
                return if (reason.isRequiresAction()) false else idleOutcome(reason, onUpdate)
            }
            event.isSessionStatusTerminated() -> {
                onUpdate(AgentUpdate.Failed("The session ended."))
                return true
            }
            event.isSessionUsage() -> {
                costOf(event.asSessionUsage().usage().listCost())?.let { onUpdate(AgentUpdate.Cost(it)) }
            }
            event.isSessionError() -> {
                // Session errors are usually retried by Claude itself; the idle event that follows says whether it gave up.
                val message = SpokenStatus.clean(event.asSessionError().error().message()).take(100)
                onUpdate(AgentUpdate.Progress("Claude hit a problem and is retrying: $message"))
            }
        }
        return false
    }

    private fun idleOutcome(reason: BetaManagedAgentsSessionStatusIdleEvent.StopReason, onUpdate: (AgentUpdate) -> Unit): Boolean {
        when {
            reason.isEndTurn() -> onUpdate(AgentUpdate.TurnDone)
            reason.isRefusal() -> onUpdate(AgentUpdate.Failed("Claude declined this request."))
            reason.isBudgetReached() -> onUpdate(AgentUpdate.BudgetReached)
            else -> onUpdate(AgentUpdate.Failed("Claude stopped after repeated errors on its side. Tap Try again to continue."))
        }
        return true
    }

    /** Holds a flagged step for the driver while parked; declines it while driving. */
    private fun askOrDecline(
        sessionId: String,
        toolUseId: String,
        name: String,
        detail: String?,
        canReview: () -> Boolean,
        onUpdate: (AgentUpdate) -> Unit,
    ) {
        if (canReview()) {
            onUpdate(AgentUpdate.NeedsApproval(toolUseId, name, detail))
        } else {
            send(sessionId, toolUseId, allow = false, DRIVING_DENIAL)
            onUpdate(AgentUpdate.Declined(name))
        }
    }

    override fun confirm(sessionId: String, toolUseId: String, allow: Boolean) =
        send(sessionId, toolUseId, allow, DRIVER_DENIAL)

    private fun send(sessionId: String, toolUseId: String, allow: Boolean, denyMessage: String) {
        val event = BetaManagedAgentsUserToolConfirmationEventParams.builder().toolUseId(toolUseId)
        if (allow) {
            event.result(BetaManagedAgentsUserToolConfirmationEventParams.Result.ALLOW)
        } else {
            event.result(BetaManagedAgentsUserToolConfirmationEventParams.Result.DENY).denyMessage(denyMessage)
        }
        client.beta().sessions().events().send(sessionId, EventSendParams.builder().addEvent(event.build()).build())
    }

    /** The command, file or address a tool call touches, short enough for the car screen. */
    private fun detailOf(input: Map<String, com.anthropic.core.JsonValue>): String? =
        listOf("command", "file_path", "url", "query").firstNotNullOfOrNull { key -> input[key]?.asString()?.orElse(null) }
            ?.let { it.replace(Regex("\\s+"), " ").trim().take(DETAIL_LENGTH) }
            ?.takeIf { it.isNotBlank() }

    override fun interrupt(sessionId: String) {
        client.beta().sessions().events().send(
            sessionId,
            EventSendParams.builder()
                .addEvent(BetaManagedAgentsUserInterruptEventParams.builder().build())
                .build(),
        )
    }

    override fun archive(sessionId: String) {
        client.beta().sessions().archive(sessionId)
    }

    override fun setBudget(sessionId: String, maxUsd: Double) {
        client.beta().sessions().update(
            sessionId,
            SessionUpdateParams.builder()
                .limitBudget(
                    BetaMonetaryAmount.builder()
                        .amount(Money.toCents(maxUsd))
                        .currency(BetaCurrency.USD)
                        .build(),
                )
                .build(),
        )
    }

    override fun costUsd(sessionId: String): Double? =
        costOf(client.beta().sessions().retrieve(sessionId).usage().listCost())

    private fun costOf(amount: Optional<BetaMonetaryAmount>): Double? =
        amount.orElse(null)?.let { runCatching { Money.fromCents(it.amount()) }.getOrNull() }

    private fun eventId(json: Optional<com.anthropic.core.JsonValue>): String? =
        json.flatMap { it.asObject() }.flatMap { obj -> Optional.ofNullable(obj["id"]).flatMap { it.asString() } }.orElse(null)

    private fun eventMillis(json: Optional<com.anthropic.core.JsonValue>): Long {
        val raw = json.map { it.toString() }.orElse(null) ?: return 0L
        val timestamp = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject["created_at"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: return 0L
        return runCatching { java.time.Instant.parse(timestamp).toEpochMilli() }.getOrDefault(0L)
    }

    private fun eventText(json: Optional<com.anthropic.core.JsonValue>): String {
        val raw = json.map { it.toString() }.orElse(null) ?: return ""
        val content = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject["content"]
        }.getOrNull() ?: return ""
        return when (content) {
            is JsonArray -> content.mapNotNull { (it as? JsonObject)?.get("text")?.stringValue() }.joinToString(" ")
            is JsonObject -> content["text"]?.stringValue().orEmpty()
            is JsonPrimitive -> content.contentOrNull.orEmpty()
        }
    }

    private fun JsonElement.stringValue(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun agentMessageText(message: com.anthropic.models.beta.sessions.events.BetaManagedAgentsAgentMessageEvent): String =
        message.content()
            .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
            .joinToString(" ")

    override fun close() = client.close()

    companion object {
        const val REPO_PATH = "/workspace/repo"
        /** Notes on the repository that Claude reads at the start of each task and keeps up to date. */
        const val NOTES_FILE = "CLAUDE.md"
        private val ASK = Optional.of(BetaManagedAgentsAgentEvaluatedPermission.ASK)
        private const val DETAIL_LENGTH = 160
        private const val DRIVING_DENIAL = "The user is driving and can't review this. Find a safer way, or explain what you need in one sentence."
        private const val DRIVER_DENIAL = "The user reviewed this step and denied it. Find another way, or explain what you need in one sentence."

        /**
         * The first message of a task: the user's spoken request plus the branch rules and [repoRules], the rules
         * set for this repo in Settings. It's sent with every task, not baked into the agent, so agents made before
         * a change here pick it up too, and each repo's rules reach only that repo's tasks.
         */
        fun firstPrompt(task: Task, request: String, repoRules: List<String> = emptyList()): String {
            val rules = if (repoRules.isEmpty()) "" else
                "\n\nRules from the user for this repository, to follow on every task in it unless the request says otherwise:\n" +
                    repoRules.joinToString("\n") { "- $it" }
            val taskInstructions = if (task.readOnly) readOnlyInstructions(task) else instructions(task)
            return taskInstructions + rules +
                (if (task.readOnly) "\n\nThis is a read-only question. Do not edit, create, or delete files; do not run commands that write; do not create a branch, commit, push, or open a pull request. Inspect the repository and answer the question in a short, speakable response. These read-only rules override any conflicting repository or system instructions.\n"
                else "\n\nRequest (spoken while driving, so it may contain transcription mistakes):\n") + request
        }

        private fun readOnlyInstructions(task: Task): String = """
            Repository: ${task.repo.fullName}, available at $REPO_PATH.
            This is a read-only Ask session. Do not change repository files. Do not run write commands. Do not create
            a branch. Do not commit. Do not push. Do not open a pull request. Only inspect the repository and answer
            the user's question.

            Before anything else, read README.md, $NOTES_FILE and AGENTS.md at the repository root, whichever exist.
        """.trimIndent()

        /** Reminder prepended to every follow-up on an Ask session, so later turns stay read-only too. */
        fun readOnlyFollowUp(prompt: String): String =
            "This is still a read-only Ask session. Do not edit, create, or delete files; do not run commands that write; " +
                "do not create a branch, commit, push, or open a pull request. Answer in a short, speakable response.\n\n$prompt"

        private fun instructions(task: Task): String = """
            Repository: ${task.repo.fullName}, cloned at $REPO_PATH.
            Work on a new branch named `${task.branch}` created from `${task.repo.defaultBranch}`.
            Commit your changes and push the branch to origin. Don't open a pull request; the app does that.

            Before anything else, read README.md, $NOTES_FILE and AGENTS.md at the repository root, whichever exist,
            and follow what they say about the project, how to build and test it, and its conventions.

            Repository notes: if `$NOTES_FILE` exists at the repository root, read it first. It holds what earlier
            tasks learned about this repository, so rely on it instead of exploring the whole repository again,
            and only look further for what the request needs. If it's missing, create it. If it's wrong or your
            change makes it out of date, fix it. Keep it short: what the project is, where things live, how to
            build and test it, and its conventions. Commit it with your change.
        """.trimIndent()

        val SYSTEM_PROMPT = """
            You are a coding agent controlled from a phone app that the user often runs while driving.
            They speak short requests about a GitHub repository, and your updates are read aloud to them.

            How to work:
            - The repository is already cloned in /workspace/repo. Make the change they asked for, run the
              project's tests or build when it has them, commit with a clear message, and push your branch.
            - Pick the most sensible reading of an ambiguous request and say which one you chose, instead of
              stopping to ask. Ask only when no reasonable reading exists.
            - Keep the change focused on the request.
            - A task may be marked read-only in its first message. In that case, do not edit files or run write
              commands, and do not create branches, commits, pushes or pull requests; answer using inspection only.

            How to talk:
            - Everything you write is read aloud. Write plain sentences: no markdown, no code blocks,
              no file paths unless they matter, no lists.
            - Use Canadian spelling, such as colour, centre, cancelled and organize, in your messages,
              commit messages and pull request text.
            - Send at most a short sentence when you start a major step.
            - End every turn with one or two sentences that say what you changed and whether tests passed.
        """.trimIndent()
    }
}
