package dev.aiauto

import android.content.Context
import androidx.car.app.connection.CarConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.aiauto.core.AgentSetup
import dev.aiauto.core.Assistant
import dev.aiauto.core.AssistantActions
import dev.aiauto.core.EngineConfig
import dev.aiauto.core.GitHub
import dev.aiauto.core.RepoRef
import dev.aiauto.core.Projects
import dev.aiauto.core.TaskEngine
import dev.aiauto.data.AvailableRepoStore
import dev.aiauto.data.ErrorStore
import dev.aiauto.data.FileChatMemory
import dev.aiauto.core.VoiceControls
import dev.aiauto.data.FileTaskStore
import dev.aiauto.data.RepoStore
import dev.aiauto.data.Secrets
import dev.aiauto.data.Settings
import dev.aiauto.data.TemplateStore
import dev.aiauto.notify.Notifier
import dev.aiauto.notify.Speaker
import dev.aiauto.notify.TonePlayer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** App-wide objects. Created once in [AiAutoApp]. */
class Graph(context: Context) {
    private val appContext = context.applicationContext
    val secrets = Secrets(context)
    val settings = Settings(context)
    val tasks = FileTaskStore(context)
    val repos = RepoStore(context)
    val availableRepos = AvailableRepoStore(context)
    val chatMemory = FileChatMemory(context)
    /** Pictures shared into tasks, kept until their first prompt is sent. */
    val imageStash = dev.aiauto.data.ImageStash(context)
    /** Recent errors for Settings. The app makes it before anything else; this covers a Graph made without it. */
    val errors: ErrorStore = AppLog.store ?: ErrorStore(context).also { AppLog.store = it }
    val templates = TemplateStore(context)
    val speaker = Speaker(context, settings)
    val tones = TonePlayer(context)
    val notifier = Notifier(context, settings, speaker)
    // Anything that slips past the engine's own error handling is logged instead of crashing the app.
    val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e -> AppLog.e("Background work failed", e) })

    val engine = TaskEngine(
        store = tasks,
        config = object : EngineConfig {
            override fun anthropicKey() = secrets.anthropicKey
            override fun githubToken() = secrets.githubToken
            override fun agentSetup() = settings.agentSetup
            override fun saveAgentSetup(setup: AgentSetup) { settings.agentSetup = setup }
            override fun agentProfile() = settings.agentProfile
            override fun deviceId() = settings.deviceId
            override fun quietMode() = settings.quietMode
            override fun setQuietMode(on: Boolean) { settings.quietMode = on }
            override fun dailyLimitUsd() = settings.dailyLimitUsd
            override fun spentTodayUsd() = settings.spentTodayUsd
            override fun monthlyLimitUsd() = settings.monthlyLimitUsd
            override fun spentThisMonthUsd() = settings.spentThisMonthUsd
            override fun addSpend(usd: Double) = this@Graph.addSpend(usd)
            override fun repoSettings(repo: RepoRef) = settings.repoSettings(repo)
            override fun saveImages(taskId: String, images: List<dev.aiauto.core.TaskImage>) = imageStash.save(taskId, images)
            override fun images(taskId: String) = imageStash.load(taskId)
            override fun dropImages(taskId: String) = imageStash.drop(taskId)
        },
        scope = scope,
        onMessage = { task, message ->
            notifier.onMessage(task, message)
            TaskService.ensureRunning(context)
        },
        onProgress = { _, text -> notifier.onProgress(text) },
        onAlert = { task, kind, text ->
            notifier.alert(task, kind, text)
            TaskService.ensureRunning(context)
        },
    )

    /** Status, stop and merge by voice. */
    private val voiceControls = VoiceControls(
        store = tasks,
        repos = { repos.repos.value },
        engine = engine,
        github = { secrets.githubToken?.let { GitHub(it) } },
        merge = ::mergePull,
    )

    /** The spoken conversation behind the car's mic button. */
    val assistant = Assistant(
        apiKey = { secrets.chatKey(it) },
        actions = object : AssistantActions {
            override fun context(): String {
                val repoList = repoChoices().take(CHAT_REPOS).joinToString(", ") { it.fullName }.ifEmpty { "none" }
                val recent = tasks.all().filter { !it.archived }.take(6).joinToString("\n") { t ->
                    "- task ${t.id} in ${t.repo.fullName}: ${t.title} (${dev.aiauto.car.TasksScreen.statusLabel(t)})" +
                        (t.lastMessage?.let { ". Last said: ${it.text.take(300)}" } ?: "")
                }.ifEmpty { "none" }
                val saved = templates.templates.value.joinToString("\n") { "- ${it.name}: ${it.prompt}" }.ifEmpty { "none" }
                val localTime = java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, h:mm a", java.util.Locale.US))
                return "Local time now: $localTime\nRepos, most recently used first: $repoList\nRecent tasks:\n$recent\nTask templates (saved requests the driver can start by name):\n$saved"
            }

            override fun startTask(repo: String, request: String): String {
                require(request.isNotBlank()) { "The request is empty." }
                val match = repoBySpokenName(repo)
                val task = engine.start(match, request)
                TaskService.ensureRunning(appContext)
                return "Started task ${task.id} in ${match.fullName}."
            }

            override fun startTaskLater(repo: String, request: String, minutes: Int): String {
                require(request.isNotBlank()) { "The request is empty." }
                val match = repoBySpokenName(repo)
                val now = System.currentTimeMillis()
                val at = dev.aiauto.core.StartLater.inMinutes(now, minutes)
                val task = engine.start(match, request, startAtMillis = at)
                return "Saved task ${task.id} in ${match.fullName}. It starts ${dev.aiauto.core.StartLater.spoken(at, now)}."
            }

            override fun startIssue(repo: String, issue: dev.aiauto.core.GitHubIssue): String {
                val match = repoBySpokenName(repo)
                val task = engine.startIssue(match, issue)
                TaskService.ensureRunning(appContext)
                return "Started issue ${issue.number} in ${match.fullName}."
            }

            override fun issue(repo: String, number: Int): dev.aiauto.core.GitHubIssue {
                val token = secrets.githubToken ?: throw IllegalStateException("Add a GitHub token in the app on your phone first.")
                return GitHub(token).issue(repoBySpokenName(repo), number)
            }

            override fun openIssues(repo: String): String {
                val token = secrets.githubToken ?: throw IllegalStateException("Add a GitHub token in the app on your phone first.")
                val target = repoBySpokenName(repo)
                val issues = GitHub(token).openIssues(target)
                return if (issues.isEmpty()) "There are no open issues in ${target.name}."
                else "Open issues in ${target.name}: " +
                    issues.joinToString(". ") { "Issue ${it.number}: ${dev.aiauto.core.SpokenStatus.clean(it.title)}" } +
                    ". Say which issue to start."
            }

            override fun quiet(): String {
                if (settings.quietMode) return "Quiet mode is already on."
                settings.quietMode = true
                return "Quiet mode is on. I'll only read out results and alerts."
            }

            override fun replyToTask(taskId: String, text: String): String {
                val task = tasks.get(taskId) ?: throw IllegalArgumentException("No task with id $taskId.")
                engine.prompt(task.id, text)
                TaskService.ensureRunning(appContext)
                return "Sent to task ${task.id}."
            }

            override fun addRepo(name: String): String {
                val token = secrets.githubToken ?: throw IllegalStateException("Add a GitHub token in the app on your phone first.")
                require(name.isNotBlank()) { "No repo name was given." }
                val github = GitHub(token)
                val exact = RepoRef.parse(name)
                val repo = if (exact != null) {
                    github.repo(exact.owner, exact.name)
                } else {
                    val mine = github.myRepos()
                    val matches = RepoRef.matchSpoken(name, mine)
                    when {
                        matches.size == 1 -> matches.first()
                        matches.isEmpty() -> throw IllegalArgumentException(
                            "None of your GitHub repos is called \"$name\". Recent ones: ${mine.take(5).joinToString { it.name }}",
                        )
                        else -> throw IllegalArgumentException("More than one repo matches: ${matches.joinToString { it.fullName }}. Say the owner too.")
                    }
                }
                if (repos.find(repo.fullName) != null) return "${repo.fullName} is already saved."
                repos.add(repo)
                return "Added ${repo.fullName}."
            }

            override fun status(repo: String, prNumber: Int?, taskId: String) = voiceControls.status(repo, prNumber, taskId)

            override fun stopTask(taskId: String) = voiceControls.stop(taskId)

            override fun previewMerge(repo: String, prNumber: Int?) = voiceControls.previewMerge(repo, prNumber)

            override fun merge(repo: RepoRef, number: Int) = voiceControls.merge(repo, number)

            override fun previewClose(repo: String, prNumber: Int?, reopen: Boolean) =
                voiceControls.previewClose(repo, prNumber, reopen)

            override fun close(repo: RepoRef, number: Int) = voiceControls.close(repo, number)

            override fun reopen(repo: RepoRef, number: Int) = voiceControls.reopen(repo, number)
        },
        canSpend = { engine.canSpend() },
        onSpend = { addSpend(it) },
        profile = { settings.chatProfile },
        memory = { if (settings.rememberChats) chatMemory else dev.aiauto.core.NoChatMemory },
    )

    /**
     * Adds to today's and this month's spend, and warns once when a total passes 80% of its limit, so the driver
     * hears it coming before Claude stops.
     */
    @Synchronized
    private fun addSpend(usd: Double) {
        val dayBefore = settings.spentTodayUsd
        val monthBefore = settings.spentThisMonthUsd
        settings.addSpend(usd)
        dev.aiauto.core.SpendWarning.after(
            dayBefore, dayBefore + usd, settings.dailyLimitUsd,
            monthBefore, monthBefore + usd, settings.monthlyLimitUsd,
        )?.let(notifier::spendWarning)
    }

    private var briefing: String? = null

    /** The welcome-back summary waiting to be read, if any. The car's mic screen takes it to read it itself. */
    @Synchronized
    fun takeBriefing(): String? = briefing.also { briefing = null }

    /** Works out the summary of what happened since the last drive and reads it if the mic screen doesn't. */
    private fun prepareBriefing() {
        if (!settings.briefingOnConnect || !isConfigured) return
        val since = maxOf(settings.lastDriveEndedMillis, System.currentTimeMillis() - BRIEFING_WINDOW_MILLIS)
        scope.launch(Dispatchers.IO) {
            val currentTasks = tasks.all()
            val external = mutableListOf<String>()
            val token = secrets.githubToken
            if (token != null) {
                val github = GitHub(token)
                val taskPulls = currentTasks.mapNotNull { task -> task.repo.fullName to task.prNumber }.toSet()
                repos.repos.value.forEach { repo ->
                    runCatching {
                        val latest = github.latestCommit(repo)
                        if (latest?.timeMillis?.let { it > since } == true) {
                            val checks = github.checks(repo, latest.sha)
                            if (checks.state == dev.aiauto.core.Checks.Failing) external += "${repo.name}'s main branch checks failed."
                        }
                        github.openPulls(repo, 10).filter { pull ->
                            (pull.updatedAt?.let(::millis) ?: 0L) > since && (repo.fullName to pull.number) !in taskPulls
                        }.forEach { pull -> external += "Pull request ${pull.number} in ${repo.name} needs review: ${pull.title}." }
                        github.issuesUpdatedSince(repo, since).filter { it.pullRequest == null }.forEach { issue ->
                            external += "Issue ${issue.number} in ${repo.name} changed: ${issue.title}."
                        }
                    }.onFailure { AppLog.w("Couldn't widen the drive briefing for ${repo.fullName}", it) }
                }
            }
            val text = dev.aiauto.core.Briefing.since(
                currentTasks, since, settings.spentTodayUsd, settings.spentThisMonthUsd, settings.monthlyLimitUsd,
                externalUpdates = external,
            ) ?: return@launch
            synchronized(this@Graph) { briefing = text }
            kotlinx.coroutines.delay(BRIEFING_DELAY_MILLIS)
            takeBriefing()?.let { if (settings.readAloudInCar && speaker.inCar) speaker.speak(it) }
        }
    }

    private fun millis(value: String): Long = runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

    /** Every repo a new task can start in: saved ones plus the ones the token can push to, most recently used first. */
    fun repoChoices(): List<RepoRef> = RepoRef.choices(repos.repos.value, availableRepos.repos.value, tasks.all())

    private fun repoBySpokenName(spoken: String): RepoRef {
        val all = repoChoices()
        return all.firstOrNull { it.fullName.equals(spoken, ignoreCase = true) }
            ?: RepoRef.matchSpoken(spoken, all).singleOrNull()
            ?: throw IllegalArgumentException(
                "No unique repo matches \"$spoken\". Recent ones: ${all.take(CHAT_REPOS).joinToString { it.fullName }}",
            )
    }

    /** Fetches the repos the token can push to in the background. On failure the last list stays. */
    fun refreshAvailableRepos() {
        val token = secrets.githubToken ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { GitHub(token).myRepos() }
                .onSuccess { availableRepos.set(it) }
                .onFailure { AppLog.w("Couldn't list the token's repos", it) }
        }
    }

    /** Keeps repos, tasks and settings the same on every device signed in to the same GitHub account. */
    val sync = dev.aiauto.data.SyncManager(settings, secrets, tasks, repos, chatMemory, scope) { id ->
        notifier.clear(id)
        engine.clear(id)
    }

    /** Each repo's status straight from GitHub, for the project screens on the phone and in the car. */
    val projects = Projects(githubToken = { secrets.githubToken }, scope = scope)

    /** Merges a pull request from the project view, then refreshes any task that owns it. Blocking. */
    fun mergePull(repo: dev.aiauto.core.RepoRef, number: Int): String {
        val task = tasks.all().firstOrNull { it.repo.fullName == repo.fullName && it.prNumber == number }
        val message = if (task != null) engine.mergeNow(task.id) else projects.merge(repo, number)
        projects.refresh(repo)
        return message
    }

    /** Opens the "Talk to Claude" conversation each time the phone connects to the car, and removes it after. */
    fun watchCar(context: Context) {
        var connected = false
        runCatching {
            CarConnection(context.applicationContext).type.observeForever { type ->
                val now = type == CarConnection.CONNECTION_TYPE_PROJECTION || type == CarConnection.CONNECTION_TYPE_NATIVE
                try {
                    if (now && !connected && isConfigured) {
                        notifier.startChat()
                        prepareBriefing()
                    }
                    if (!now && connected) {
                        notifier.clearChat()
                        settings.lastDriveEndedMillis = System.currentTimeMillis()
                    }
                    // Make sure the foreground service holds running tasks before and after the car goes away,
                    // so work started from the car keeps going on the phone until it's done.
                    if (now != connected && hasActiveWork()) TaskService.ensureRunning(context)
                } catch (e: Throwable) {
                    AppLog.w("Car connection change failed", e)
                }
                connected = now
            }
        }.onFailure { AppLog.w("Couldn't watch the car connection", it) }
    }

    /** True while a task is running or waiting to reconnect, so the foreground service should be up. */
    fun hasActiveWork(): Boolean = tasks.all().any { engine.isRunning(it.id) } || engine.isReconnecting()

    /** Keeps the alarm for tasks saved for later set to the next one due, whenever the task list changes. */
    fun watchStartLater(context: Context) {
        val app = context.applicationContext
        scope.launch {
            tasks.tasks.collect {
                dev.aiauto.notify.StartLaterAlarm.schedule(app, engine.nextStartMillis())
                delay(1_000)
            }
        }
    }

    /** Keeps the home-screen widget in step with the task list, at most about once a second. */
    fun watchWidget(context: Context) {
        val app = context.applicationContext
        scope.launch {
            tasks.tasks.collect {
                dev.aiauto.widget.TasksWidget.refresh(app)
                delay(1_000)
            }
        }
    }

    /**
     * Resumes tasks that stopped because the connection dropped, answers car chat questions that couldn't get
     * through, and kicks a sync, as soon as the phone is back online.
     */
    fun watchNetwork(context: Context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                    try {
                        engine.onNetworkAvailable()
                        if (hasActiveWork()) TaskService.ensureRunning(context)
                    } catch (e: Throwable) {
                        AppLog.w("Couldn't resume tasks after the network came back", e)
                    }
                    sync.syncSoon()
                    scope.launch(Dispatchers.IO) { sendQueuedChat() }
                }
            })
        }.onFailure { AppLog.w("Couldn't watch the network", it) }
    }

    /** Answers the car chat questions kept while there was no signal, in the chat conversation and out loud in the car. */
    private fun sendQueuedChat() {
        if (!assistant.hasQueued()) return
        assistant.sendQueued().forEach { answer ->
            notifier.postChat(fromClaude = answer)
            if (speaker.inCar) speaker.speak(answer)
        }
    }

    val isConfigured: Boolean get() = secrets.anthropicKey != null && secrets.githubToken != null

    private companion object {
        /** How many repos the car chat hears about and can name in an error, to keep its prompt short. */
        const val CHAT_REPOS = 15
        /** Gives the car screen time to open and read the briefing itself, before music starts. */
        const val BRIEFING_DELAY_MILLIS = 8_000L
        /** Never brief on more than the last day, even after a long time away. */
        const val BRIEFING_WINDOW_MILLIS = 24 * 60 * 60_000L
    }
}
