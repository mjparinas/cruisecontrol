package dev.aiauto

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Icon
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import dev.aiauto.ui.ReviewScreen
import dev.aiauto.ui.SettingsScreen
import dev.aiauto.ui.SettingsState
import dev.aiauto.ui.SetupChecklistScreen
import dev.aiauto.ui.TaskScreen
import dev.aiauto.ui.TalkScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Screen {
    data object Home : Screen
    data object NewTask : Screen
    data object Settings : Screen
    data object Projects : Screen
    data object Review : Screen
    data object Setup : Screen
    data object Talk : Screen
    data class Detail(val taskId: String) : Screen
}

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf<Screen>(Screen.Home)
    private var sharedRequest by mutableStateOf<String?>(null)
    private var shortcutRequest by mutableStateOf<Pair<RepoRef, String>?>(null)
    private var confirmMergeTask by mutableStateOf<String?>(null)
    private var autoListen by mutableStateOf(false)
    private var lifecycleTick by mutableIntStateOf(0)

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
        g.refreshAvailableRepos()
        // A recreated activity or one reopened from Recents still carries the shortcut, which already started its task.
        val fresh = savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
        sharedRequest = intent.sharedRequest()
        autoListen = intent.action == ACTION_WIDGET_MIC
        confirmMergeTask = intent.getStringExtra(EXTRA_TASK)?.takeIf { intent.getBooleanExtra(EXTRA_CONFIRM_MERGE, false) }
        screen = intent.taskTarget() ?: intent.takeIf { fresh }?.startFromShortcut() ?: if (sharedRequest != null) Screen.NewTask else if (g.isConfigured) Screen.Home else Screen.Settings
        if (intent.action == ACTION_OPEN_NEW_TASK || intent.action == ACTION_WIDGET_MIC) screen = Screen.NewTask
        if (intent.action == ACTION_TALK_TO_CLAUDE) { screen = Screen.Talk; autoListen = true }
        installLauncherShortcuts()

        setContent {
            var themeMode by remember { mutableStateOf(enumOrDefault(g.settings.themeMode, ThemeMode.System)) }
            var accent by remember { mutableStateOf(enumOrDefault(g.settings.accent, Accent.Clay)) }
            AiAutoTheme(mode = themeMode, accent = accent) {
                dev.aiauto.update.UpdateDialog((application as AiAutoApp).updater)
                val tasks by g.tasks.tasks.collectAsStateWithLifecycle()
                val repos by g.repos.repos.collectAsStateWithLifecycle()
                val availableRepos by g.availableRepos.repos.collectAsStateWithLifecycle()
                val repoChoices = remember(repos, availableRepos, tasks) { RepoRef.choices(repos, availableRepos, tasks) }
                val templates by g.templates.templates.collectAsStateWithLifecycle()
                val views by g.projects.repos.collectAsStateWithLifecycle()
                val errors by g.errors.errors.collectAsStateWithLifecycle()
                var projectMessage by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()
                var settings by remember { mutableStateOf(settingsState(repos)) }

                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

                when (val s = screen) {
                    Screen.Home -> HomeScreen(
                        tasks = tasks,
                        hasRepos = repoChoices.isNotEmpty() && g.isConfigured,
                        onOpen = { screen = Screen.Detail(it.id) },
                        onArchive = { g.engine.archive(it.id); g.notifier.clear(it.id) },
                        onUnarchive = { g.engine.unarchive(it.id) },
                        onClear = { g.engine.clear(it.id); g.notifier.clear(it.id) },
                        onNew = {
                            g.refreshAvailableRepos()
                            screen = Screen.NewTask
                        },
                        onSettings = { screen = Screen.Settings },
                        onProjects = {
                            projectMessage = null
                            g.projects.refreshAll(repos, maxAgeMillis = 30_000)
                            screen = Screen.Projects
                        },
                        onReview = { screen = Screen.Review },
                        finishedCount = tasks.count { it.isFinished && !it.archived && !g.engine.isRunning(it.id) },
                        onClearFinished = { tasks.filter { it.isFinished && !it.archived && !g.engine.isRunning(it.id) }.forEach { g.notifier.clear(it.id) }; g.engine.clearFinished() },
                        spentToday = g.settings.spentTodayUsd,
                        dailyLimit = g.settings.dailyLimitUsd,
                        spentThisMonth = g.settings.spentThisMonthUsd,
                        monthlyLimit = g.settings.monthlyLimitUsd,
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
                    Screen.Review -> ReviewScreen(
                        tasks = tasks,
                        githubToken = g.secrets.githubToken,
                        onBack = { screen = Screen.Home },
                        onApprove = { task ->
                            scope.launch {
                                val message = withContext(Dispatchers.IO) {
                                    runCatching { g.secrets.githubToken?.let { dev.aiauto.core.GitHub(it).approvePull(task.repo, task.prNumber!!) }; "Approved pull request #${task.prNumber}." }
                                        .getOrElse { "Couldn't approve pull request. ${Failures.describe(it)}" }
                                }
                                android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                        onMerge = { task ->
                            scope.launch {
                                val message = withContext(Dispatchers.IO) { runCatching { g.engine.mergeNow(task.id) }.getOrElse { "Couldn't merge pull request. ${Failures.describe(it)}" } }
                                android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                    Screen.Setup -> SetupChecklistScreen(
                        hasAnthropicKey = g.secrets.anthropicKey != null,
                        hasGithubToken = g.secrets.githubToken != null,
                        githubToken = g.secrets.githubToken,
                        repos = repos,
                        refreshKey = lifecycleTick,
                        onBack = { screen = Screen.Settings },
                        onSettings = { screen = Screen.Settings },
                    )
                    Screen.Talk -> TalkScreen(
                        onBack = { screen = Screen.Home },
                        autoListen = autoListen,
                        onAutoListenHandled = { autoListen = false },
                        onAsk = { text -> g.assistant.ask(text) },
                    )
                    Screen.NewTask -> NewTaskScreen(
                        repos = repoChoices,
                        lastUsed = shortcutRequest?.first ?: tasks.maxByOrNull { it.createdMillis }?.repo,
                        templates = templates,
                        onAddTemplate = { name, prompt -> g.templates.add(name, prompt) },
                        onDeleteTemplate = { g.templates.remove(it.id) },
                        onStartAsk = { repo, text ->
                            val task = g.engine.startAsk(repo, text)
                            TaskService.ensureRunning(this)
                            shortcutRequest = null
                            screen = Screen.Detail(task.id)
                        },
                        listenUntilStop = g.settings.listenUntilStop,
                        sharedText = sharedRequest,
                        initialRequest = shortcutRequest,
                        onReadBack = { spoken, done ->
                            if (g.speaker.inCar) g.speaker.speak(spoken, done) else done()
                        },
                        autoListen = autoListen,
                        onAutoListenHandled = { autoListen = false },
                        onStartShared = { repo, text ->
                            val task = g.engine.start(repo, text)
                            TaskService.ensureRunning(this)
                            sharedRequest = null
                            shortcutRequest = null
                            screen = Screen.Detail(task.id)
                        },
                        onBack = { sharedRequest = null; shortcutRequest = null; screen = Screen.Home },
                        onStart = { repo, text ->
                            val task = g.engine.start(repo, text)
                            TaskService.ensureRunning(this)
                            sharedRequest = null
                            shortcutRequest = null
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
                            listenUntilStop = g.settings.listenUntilStop,
                            confirmMergeOnOpen = confirmMergeTask == task.id,
                            onMergePromptShown = { confirmMergeTask = null },
                        ) { text ->
                            g.engine.prompt(task.id, text)
                            TaskService.ensureRunning(this)
                        }
                    }
                    Screen.Settings -> SettingsScreen(
                        state = settingsState(repos).copy(
                            busy = settings.busy, message = settings.message, themeMode = themeMode, accent = accent, errors = errors,
                        ),
                        onBack = { screen = Screen.Home },
                        onSetupChecklist = { screen = Screen.Setup },
                        onClearErrors = { g.errors.clear() },
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
                        onRepoSettings = { repo, changed ->
                            g.settings.setRepoSettings(repo, changed)
                            settings = settings.copy(repoSettings = g.settings.repoSettings, message = "Saved settings for ${repo.name}.")
                        },
                        onReadAloud = { g.settings.readAloudInCar = it; settings = settings.copy(readAloud = it) },
                        onQuietMode = { g.settings.quietMode = it; settings = settings.copy(quietMode = it) },
                        onThemeMode = { g.settings.themeMode = it.name; themeMode = it },
                        onAccent = { g.settings.accent = it.name; accent = it },
                        onDailyLimit = {
                            g.settings.dailyLimitUsd = it
                            settings = settings.copy(
                                dailyLimitUsd = it,
                                message = it?.let { v -> "Daily limit set to ${dev.aiauto.core.SpokenStatus.money(v)}." } ?: "No daily limit.",
                            )
                        },
                        onMonthlyLimit = {
                            g.settings.monthlyLimitUsd = it
                            settings = settings.copy(
                                monthlyLimitUsd = it,
                                message = it?.let { v -> "Monthly limit set to ${dev.aiauto.core.SpokenStatus.money(v)}." } ?: "No monthly limit.",
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
                        onWheelButtons = { g.settings.wheelButtons = it; settings = settings.copy(wheelButtons = it) },
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
        lifecycleTick++
        // Pull request state and checks change on GitHub while the app is closed.
        graph.engine.refreshPullRequests()
        graph.sync.syncSoon()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.sharedRequest()?.let { sharedRequest = it; screen = Screen.NewTask }
        if (intent.action == ACTION_WIDGET_MIC) { autoListen = true; screen = Screen.NewTask }
        if (intent.action == ACTION_TALK_TO_CLAUDE) { autoListen = true; screen = Screen.Talk }
        confirmMergeTask = intent.getStringExtra(EXTRA_TASK)?.takeIf { intent.getBooleanExtra(EXTRA_CONFIRM_MERGE, false) }
        (intent.taskTarget() ?: intent.startFromShortcut())?.let { screen = it }
        if (intent.action == ACTION_OPEN_NEW_TASK) screen = Screen.NewTask
    }

    private fun installLauncherShortcuts() {
        if (Build.VERSION.SDK_INT < 25) return
        val manager = getSystemService(android.content.pm.ShortcutManager::class.java) ?: return
        val base = Intent(this, MainActivity::class.java).setAction(ACTION_WIDGET_MIC)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        manager.dynamicShortcuts = listOf(
            ShortcutInfo.Builder(this, "new_task")
                .setShortLabel("New task")
                .setLongLabel("Start a new Cruise Control task")
                .setIcon(Icon.createWithResource(this, R.drawable.ic_glyph))
                .setIntent(Intent(base))
                .build(),
            ShortcutInfo.Builder(this, "last_repo")
                .setShortLabel("Last repo")
                .setLongLabel("Start a task in your last used repo")
                .setIcon(Icon.createWithResource(this, R.drawable.ic_glyph))
                .setIntent(Intent(base).putExtra(EXTRA_LAST_REPO, true))
                .build(),
            ShortcutInfo.Builder(this, "talk_to_claude")
                .setShortLabel("Talk to Claude")
                .setLongLabel("Talk to Claude by voice")
                .setIcon(Icon.createWithResource(this, R.drawable.ic_glyph))
                .setIntent(Intent(this, MainActivity::class.java).setAction(ACTION_TALK_TO_CLAUDE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                .build(),
        )
    }

    private fun Intent.sharedRequest(): String? = if (action == Intent.ACTION_SEND && type?.startsWith("text/") == true)
        extras?.get(Intent.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() } else null

    /**
     * Starts the task a Google Assistant shortcut asked for and says so, or says what's missing. Null when the
     * intent isn't from the shortcut. Android stops background apps from opening activities, so only the Assistant
     * or an app the user has open can get here, and the new task shows on screen with its Stop button.
     */
    private fun Intent.startFromShortcut(): Screen? {
        if (action != ACTION_START_TASK) return null
        val g = graph
        val repos = g.repoChoices()
        val result = dev.aiauto.core.VoiceShortcut.resolve(
            spoken = getStringExtra(EXTRA_REQUEST).orEmpty(),
            repos = repos,
            lastUsed = g.tasks.all().maxByOrNull { it.createdMillis }?.repo,
            templates = g.templates.templates.value,
            configured = g.isConfigured,
        )
        return when (result) {
            is dev.aiauto.core.VoiceShortcut.Start -> {
                shortcutRequest = result.repo to result.request
                Screen.NewTask
            }
            is dev.aiauto.core.VoiceShortcut.Problem -> {
                if (g.settings.readAloudInCar && g.speaker.inCar) g.speaker.speak(result.spoken)
                else android.widget.Toast.makeText(this@MainActivity, result.spoken, android.widget.Toast.LENGTH_LONG).show()
                if (g.isConfigured && repos.isNotEmpty()) Screen.NewTask else Screen.Settings
            }
        }
    }

    private fun settingsState(repos: List<RepoRef>) = SettingsState(
        hasAnthropicKey = graph.secrets.anthropicKey != null,
        hasGithubToken = graph.secrets.githubToken != null,
        repos = repos,
        readAloud = graph.settings.readAloudInCar,
        quietMode = graph.settings.quietMode,
        repoSettings = graph.settings.repoSettings,
        dailyLimitUsd = graph.settings.dailyLimitUsd,
        spentTodayUsd = graph.settings.spentTodayUsd,
        monthlyLimitUsd = graph.settings.monthlyLimitUsd,
        spentThisMonthUsd = graph.settings.spentThisMonthUsd,
        version = (application as AiAutoApp).updater.currentVersion,
        themeMode = enumOrDefault(graph.settings.themeMode, ThemeMode.System),
        accent = enumOrDefault(graph.settings.accent, Accent.Clay),
        agentProfile = graph.settings.agentProfile,
        chatProfile = graph.settings.chatProfile,
        speechRate = graph.settings.speechRate,
        voiceName = graph.settings.voiceName,
        listenOnOpen = graph.settings.listenOnOpen,
        listenUntilStop = graph.settings.listenUntilStop,
        wheelButtons = graph.settings.wheelButtons,
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
        const val EXTRA_CONFIRM_MERGE = "confirm_merge"
        const val EXTRA_LAST_REPO = "last_repo"
        const val ACTION_OPEN_NEW_TASK = "dev.aiauto.action.OPEN_NEW_TASK"
        const val ACTION_WIDGET_MIC = "dev.aiauto.action.WIDGET_MIC"
        const val ACTION_TALK_TO_CLAUDE = "dev.aiauto.action.TALK_TO_CLAUDE"

        /** The Google Assistant shortcut's action and spoken request, declared in res/xml/shortcuts.xml. */
        const val ACTION_START_TASK = "dev.aiauto.action.START_TASK"
        const val EXTRA_REQUEST = "request"
    }
}
