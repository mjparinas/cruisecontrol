package dev.aiauto

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aiauto.core.Failures
import dev.aiauto.core.GitHub
import dev.aiauto.core.RepoRef
import dev.aiauto.ui.Accent
import dev.aiauto.ui.AiAutoTheme
import dev.aiauto.ui.ThemeMode
import dev.aiauto.ui.HomeScreen
import dev.aiauto.ui.NewTaskScreen
import dev.aiauto.ui.ProjectsScreen
import dev.aiauto.ui.SettingsScreen
import dev.aiauto.ui.SettingsState
import dev.aiauto.ui.TaskScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Screen {
    data object Home : Screen
    data object NewTask : Screen
    data object Settings : Screen
    data object Projects : Screen
    data class Detail(val taskId: String) : Screen
}

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf<Screen>(Screen.Home)

    /**
     * Asks once to lift battery limits. Without it, Android (Samsung especially) can stop the app soon after
     * Android Auto disconnects, which stops Claude's running tasks.
     */
    @android.annotation.SuppressLint("BatteryLife")
    private fun askToRunInBackground() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (TaskService.isUnrestricted(this) || prefs.getBoolean("asked_battery", false)) return
        prefs.edit().putBoolean("asked_battery", true).apply()
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:$packageName")),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The microphone is for speaking requests from the car screen.
        val permissions = listOfNotNull(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS.takeIf { Build.VERSION.SDK_INT >= 33 })
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(permissions.toTypedArray())
        askToRunInBackground()
        val g = graph
        screen = intent.taskTarget() ?: if (g.isConfigured) Screen.Home else Screen.Settings

        setContent {
            var themeMode by remember { mutableStateOf(enumOrDefault(g.settings.themeMode, ThemeMode.System)) }
            var accent by remember { mutableStateOf(enumOrDefault(g.settings.accent, Accent.Clay)) }
            AiAutoTheme(mode = themeMode, accent = accent) {
                dev.aiauto.update.UpdateDialog((application as AiAutoApp).updater)
                val tasks by g.tasks.tasks.collectAsStateWithLifecycle()
                val repos by g.repos.repos.collectAsStateWithLifecycle()
                val templates by g.templates.templates.collectAsStateWithLifecycle()
                val views by g.projects.repos.collectAsStateWithLifecycle()
                var projectMessage by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()
                var settings by remember { mutableStateOf(settingsState(repos)) }

                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

                when (val s = screen) {
                    Screen.Home -> HomeScreen(
                        tasks = tasks,
                        hasRepos = repos.isNotEmpty() && g.isConfigured,
                        onOpen = { screen = Screen.Detail(it.id) },
                        onArchive = { g.engine.archive(it.id); g.notifier.clear(it.id) },
                        onUnarchive = { g.engine.unarchive(it.id) },
                        onClear = { g.engine.clear(it.id); g.notifier.clear(it.id) },
                        onNew = { screen = Screen.NewTask },
                        onSettings = { screen = Screen.Settings },
                        onProjects = {
                            projectMessage = null
                            g.projects.refreshAll(repos, maxAgeMillis = 30_000)
                            screen = Screen.Projects
                        },
                        finishedCount = tasks.count { it.isFinished && !it.archived && !g.engine.isRunning(it.id) },
                        onClearFinished = { tasks.filter { it.isFinished && !it.archived && !g.engine.isRunning(it.id) }.forEach { g.notifier.clear(it.id) }; g.engine.clearFinished() },
                        spentToday = g.settings.spentTodayUsd,
                        dailyLimit = g.settings.dailyLimitUsd,
                    )
                    Screen.Projects -> ProjectsScreen(
                        repos = repos,
                        views = views,
                        message = projectMessage,
                        onBack = { screen = Screen.Home },
                        onRefresh = { g.projects.refreshAll(repos) },
                        onMerge = { repo, number ->
                            projectMessage = "Merging #$number…"
                            scope.launch {
                                projectMessage = withContext(Dispatchers.IO) {
                                    runCatching { g.mergePull(repo, number) }.getOrElse { "Couldn't merge pull request $number. ${Failures.describe(it)}" }
                                }
                            }
                        },
                    )
                    Screen.NewTask -> NewTaskScreen(
                        repos = repos,
                        lastUsed = tasks.maxByOrNull { it.createdMillis }?.repo,
                        templates = templates,
                        onAddTemplate = { name, prompt -> g.templates.add(name, prompt) },
                        onDeleteTemplate = { g.templates.remove(it.id) },
                        onBack = { screen = Screen.Home },
                        onStart = { repo, text ->
                            val task = g.engine.start(repo, text)
                            TaskService.ensureRunning(this)
                            screen = Screen.Detail(task.id)
                        },
                    )
                    is Screen.Detail -> {
                        val task = tasks.firstOrNull { it.id == s.taskId }
                        if (task == null) screen = Screen.Home
                        else TaskScreen(
                            task,
                            running = g.engine.isRunning(task.id) || task.status == dev.aiauto.core.TaskStatus.Working || task.status == dev.aiauto.core.TaskStatus.Starting,
                            onBack = { screen = Screen.Home },
                            onStop = { g.engine.stop(task.id) },
                            onArchive = {
                                g.engine.archive(task.id)
                                g.notifier.clear(task.id)
                                screen = Screen.Home
                            },
                            onUnarchive = { g.engine.unarchive(task.id) },
                            onMerge = {
                                android.widget.Toast.makeText(this, "Merging…", android.widget.Toast.LENGTH_SHORT).show()
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { g.engine.mergeNow(task.id) }.getOrElse { "Couldn't merge. ${Failures.describe(it)}" }
                                    }
                                    android.widget.Toast.makeText(this@MainActivity, result, android.widget.Toast.LENGTH_LONG).show()
                                }
                            },
                            onRetry = {
                                g.engine.retry(task.id)
                                TaskService.ensureRunning(this)
                            },
                        ) { text ->
                            g.engine.prompt(task.id, text)
                            TaskService.ensureRunning(this)
                        }
                    }
                    Screen.Settings -> SettingsScreen(
                        state = settingsState(repos).copy(busy = settings.busy, message = settings.message, themeMode = themeMode, accent = accent),
                        onBack = { screen = Screen.Home },
                        onSaveKeys = { anthropic, github ->
                            val saved = runCatching {
                                if (anthropic.isNotBlank()) {
                                    g.secrets.anthropicKey = anthropic
                                    g.settings.agentSetup = null // a new key means a new agent in that account
                                }
                                if (github.isNotBlank()) g.secrets.githubToken = github
                            }
                            settings = settings.copy(
                                message = if (saved.isSuccess) "Saved." else "Couldn't save securely on this phone. Try again, or restart the app.",
                            )
                        },
                        onAddRepo = { text ->
                            val parsed = RepoRef.parse(text)
                            val token = g.secrets.githubToken
                            if (parsed == null || token == null) {
                                settings = settings.copy(message = "Enter a repo as owner/name.")
                            } else {
                                settings = settings.copy(busy = true, message = "Checking ${parsed.fullName}…")
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { runCatching { GitHub(token).repo(parsed.owner, parsed.name) } }
                                    result.onSuccess { g.repos.add(it) }
                                    settings = settings.copy(
                                        busy = false,
                                        message = result.fold({ "Added ${it.fullName}." }, { Failures.describe(it) }),
                                    )
                                }
                            }
                        },
                        onLoadGithubRepos = {
                            val token = g.secrets.githubToken
                            withContext(Dispatchers.IO) { runCatching { GitHub(token!!).myRepos() } }
                        },
                        onAddRepos = { picked ->
                            picked.forEach { g.repos.add(it) }
                            settings = settings.copy(
                                message = if (picked.size == 1) "Added ${picked[0].fullName}." else "Added ${picked.size} repositories.",
                            )
                        },
                        onRemoveRepo = { repo ->
                            g.repos.remove(repo)
                            settings = settings.copy(message = "Removed ${repo.fullName}.")
                        },
                        onReadAloud = { g.settings.readAloudInCar = it; settings = settings.copy(readAloud = it) },
                        onQuietMode = { g.settings.quietMode = it; settings = settings.copy(quietMode = it) },
                        onAutoMerge = { g.settings.autoMerge = it; settings = settings.copy(autoMerge = it) },
                        onAutoFixChecks = { g.settings.autoFixChecks = it; settings = settings.copy(autoFixChecks = it) },
                        onAutoFixConflicts = { g.settings.autoFixConflicts = it; settings = settings.copy(autoFixConflicts = it) },
                        onAutoRelease = {
                            g.settings.autoReleaseSince = if (it) System.currentTimeMillis() else null
                            settings = settings.copy(autoRelease = it)
                        },
                        onThemeMode = { g.settings.themeMode = it.name; themeMode = it },
                        onAccent = { g.settings.accent = it.name; accent = it },
                        onDailyLimit = {
                            g.settings.dailyLimitUsd = it
                            settings = settings.copy(
                                dailyLimitUsd = it,
                                message = it?.let { v -> "Daily limit set to ${dev.aiauto.core.SpokenStatus.money(v)}." } ?: "No daily limit.",
                            )
                        },
                        onAgentProfile = { g.settings.agentProfile = it; settings = settings.copy(agentProfile = it) },
                        onChatProfile = { g.settings.chatProfile = it; settings = settings.copy(chatProfile = it) },
                        voices = { g.speaker.voices() },
                        onSpeechRate = { g.settings.speechRate = it; settings = settings.copy(speechRate = it) },
                        onVoice = { g.settings.voiceName = it; settings = settings.copy(voiceName = it) },
                        onPreviewVoice = { g.speaker.stop(); g.speaker.speak("This is how I'll sound in the car.") },
                        onListenOnOpen = { g.settings.listenOnOpen = it; settings = settings.copy(listenOnOpen = it) },
                        onListenUntilStop = { g.settings.listenUntilStop = it; settings = settings.copy(listenUntilStop = it) },
                        onBriefing = { g.settings.briefingOnConnect = it; settings = settings.copy(briefing = it) },
                        onRememberChats = { g.settings.rememberChats = it; settings = settings.copy(rememberChats = it) },
                        onForgetChats = { g.chatMemory.forget(); settings = settings.copy(message = "The car chat forgot your past drives.") },
                        onSyncEnabled = { on ->
                            g.settings.syncEnabled = on
                            settings = settings.copy(syncEnabled = on, message = if (on) "Syncing…" else "Sync is off.")
                            if (on) scope.launch {
                                val result = g.sync.syncNow()
                                settings = settings.copy(message = result, syncStatus = syncStatus())
                            }
                        },
                        onSaveChatKey = { provider, key ->
                            val saved = runCatching { g.secrets.setChatKey(provider, key) }
                            settings = settings.copy(
                                message = if (saved.isSuccess) "Saved." else "Couldn't save securely on this phone. Try again, or restart the app.",
                            )
                        },
                        onSyncNow = {
                            settings = settings.copy(busy = true, message = "Syncing…")
                            scope.launch {
                                val result = g.sync.syncNow()
                                settings = settings.copy(busy = false, message = result, syncStatus = syncStatus())
                            }
                        },
                        onCheckUpdate = {
                            settings = settings.copy(busy = true, message = "Checking for updates…")
                            android.widget.Toast.makeText(this, "Checking for updates…", android.widget.Toast.LENGTH_SHORT).show()
                            scope.launch {
                                val result = (application as AiAutoApp).updater.checkNow()
                                settings = settings.copy(busy = false, message = result)
                                // The inline message sits at the bottom of a long page and is easy to miss.
                                android.widget.Toast.makeText(this@MainActivity, result, android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Pull request state and checks change on GitHub while the app is closed.
        graph.engine.refreshPullRequests()
        graph.sync.syncSoon()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.taskTarget()?.let { screen = it }
    }

    private fun settingsState(repos: List<RepoRef>) = SettingsState(
        hasAnthropicKey = graph.secrets.anthropicKey != null,
        hasGithubToken = graph.secrets.githubToken != null,
        repos = repos,
        readAloud = graph.settings.readAloudInCar,
        quietMode = graph.settings.quietMode,
        autoMerge = graph.settings.autoMerge,
        autoFixChecks = graph.settings.autoFixChecks,
        autoFixConflicts = graph.settings.autoFixConflicts,
        autoRelease = graph.settings.autoReleaseSince != null,
        dailyLimitUsd = graph.settings.dailyLimitUsd,
        spentTodayUsd = graph.settings.spentTodayUsd,
        version = (application as AiAutoApp).updater.currentVersion,
        themeMode = enumOrDefault(graph.settings.themeMode, ThemeMode.System),
        accent = enumOrDefault(graph.settings.accent, Accent.Clay),
        agentProfile = graph.settings.agentProfile,
        chatProfile = graph.settings.chatProfile,
        speechRate = graph.settings.speechRate,
        voiceName = graph.settings.voiceName,
        listenOnOpen = graph.settings.listenOnOpen,
        listenUntilStop = graph.settings.listenUntilStop,
        briefing = graph.settings.briefingOnConnect,
        rememberChats = graph.settings.rememberChats,
        hasOpenAiKey = graph.secrets.openAiKey != null,
        hasGeminiKey = graph.secrets.geminiKey != null,
        syncEnabled = graph.settings.syncEnabled,
        syncStatus = syncStatus(),
    )

    private fun syncStatus(): String {
        graph.settings.lastSyncError?.let { return it }
        val last = graph.settings.lastSyncMillis
        if (last == 0L) return "Not synced yet"
        return "Last synced " + android.text.format.DateUtils.getRelativeTimeSpanString(
            last, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
        )
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private fun Intent.taskTarget(): Screen? = getStringExtra(EXTRA_TASK)?.let { Screen.Detail(it) }

    companion object {
        const val EXTRA_TASK = "task"
    }
}
