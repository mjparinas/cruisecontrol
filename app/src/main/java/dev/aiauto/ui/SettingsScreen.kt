package dev.aiauto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.NotificationsPaused
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.aiauto.core.RepoRef
import dev.aiauto.core.SpokenStatus

data class SettingsState(
    val hasAnthropicKey: Boolean,
    val hasGithubToken: Boolean,
    val repos: List<RepoRef>,
    val readAloud: Boolean,
    val quietMode: Boolean,
    val autoMerge: Boolean,
    val autoFixChecks: Boolean,
    val autoFixConflicts: Boolean,
    val autoRelease: Boolean,
    val dailyLimitUsd: Double?,
    val spentTodayUsd: Double,
    val version: String,
    val themeMode: ThemeMode,
    val accent: Accent,
    val agentProfile: dev.aiauto.core.AgentProfile = dev.aiauto.core.AgentProfile(),
    val chatProfile: dev.aiauto.core.ChatProfile = dev.aiauto.core.ChatProfile(),
    val speechRate: Float = 1f,
    val voiceName: String? = null,
    val listenOnOpen: Boolean = true,
    val listenUntilStop: Boolean = false,
    val briefing: Boolean = true,
    val rememberChats: Boolean = true,
    val syncEnabled: Boolean = false,
    val syncStatus: String = "",
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * Settings, in the grouped style of Melvin's Tuned app: small capital headers over rounded cards, one row per setting
 * with a tinted round icon badge, the whole row tappable. Anything typed (keys, a repo, the daily limit) opens a dialog
 * from its row rather than sitting on the page as a text field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsState,
    onBack: () -> Unit,
    onSaveKeys: (anthropic: String, github: String) -> Unit,
    onAddRepo: (String) -> Unit,
    onLoadGithubRepos: suspend () -> Result<List<RepoRef>>,
    onAddRepos: (List<RepoRef>) -> Unit,
    onReadAloud: (Boolean) -> Unit,
    onQuietMode: (Boolean) -> Unit,
    onAutoMerge: (Boolean) -> Unit,
    onAutoFixChecks: (Boolean) -> Unit,
    onAutoFixConflicts: (Boolean) -> Unit,
    onAutoRelease: (Boolean) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onAccent: (Accent) -> Unit,
    onDailyLimit: (Double?) -> Unit,
    onCheckUpdate: () -> Unit,
    onAgentProfile: (dev.aiauto.core.AgentProfile) -> Unit = {},
    onChatProfile: (dev.aiauto.core.ChatProfile) -> Unit = {},
    voices: () -> List<dev.aiauto.notify.VoiceOption> = { emptyList() },
    onSpeechRate: (Float) -> Unit = {},
    onVoice: (String?) -> Unit = {},
    onPreviewVoice: () -> Unit = {},
    onListenOnOpen: (Boolean) -> Unit = {},
    onListenUntilStop: (Boolean) -> Unit = {},
    onBriefing: (Boolean) -> Unit = {},
    onRememberChats: (Boolean) -> Unit = {},
    onForgetChats: () -> Unit = {},
    onSyncEnabled: (Boolean) -> Unit = {},
    onSyncNow: () -> Unit = {},
) {
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // Up top, so the result of a dialog's Save is in view without scrolling.
            state.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }

            SectionHeader("Accounts")
            SettingsCard {
                SettingRow(
                    Icons.Rounded.Key, BadgeHue.Amber, "Claude API key",
                    if (state.hasAnthropicKey) "Saved" else "Not set. Create one at platform.claude.com.",
                    enabled = !state.busy,
                ) { dialog = SettingsDialog.AnthropicKey }
                SettingRow(
                    Icons.Rounded.Code, BadgeHue.Violet, "GitHub token",
                    if (state.hasGithubToken) "Saved" else "Not set",
                    enabled = !state.busy,
                ) { dialog = SettingsDialog.GithubToken }
            }

            SectionHeader("Repositories")
            SettingsCard {
                state.repos.forEach { repo -> SettingRow(Icons.Rounded.FolderOpen, BadgeHue.Blue, repo.fullName) }
                SettingRow(
                    Icons.Rounded.Add, BadgeHue.Green, "Add repositories",
                    if (state.hasGithubToken) "Pick from your GitHub account" else "Save a GitHub token first",
                    enabled = !state.busy && state.hasGithubToken,
                ) { dialog = SettingsDialog.PickRepos }
            }

            SectionHeader("In the car")
            SettingsCard {
                SwitchRow(
                    Icons.AutoMirrored.Rounded.VolumeUp, BadgeHue.Teal, "Read updates aloud",
                    "Speaks each update through the car while Android Auto is connected.",
                    state.readAloud, onReadAloud,
                )
                SwitchRow(
                    Icons.Rounded.NotificationsPaused, BadgeHue.Blue, "Quiet mode",
                    "Only tells you the result of each request, not every step along the way.",
                    state.quietMode, onQuietMode,
                )
                VoiceRows(state.speechRate, state.voiceName, voices, onSpeechRate, onVoice, onPreviewVoice)
                CarStartRows(
                    state.listenOnOpen, state.listenUntilStop, state.briefing, state.rememberChats,
                    onListenOnOpen, onListenUntilStop, onBriefing, onRememberChats, onForgetChats,
                )
            }

            ClaudeSettingsSection(state.agentProfile, state.chatProfile, onAgentProfile, onChatProfile)

            SectionHeader("Pull requests")
            SettingsCard {
                SwitchRow(
                    Icons.Rounded.DoneAll, BadgeHue.Green, "Merge when checks pass",
                    "Turns on GitHub auto-merge when the repo allows it. Otherwise the app merges once every check is green. Failing checks are never merged.",
                    state.autoMerge, onAutoMerge,
                )
                SwitchRow(
                    Icons.Rounded.Sync, BadgeHue.Violet, "Fix merge conflicts automatically",
                    "When a pull request conflicts with the main branch, Claude merges it in, resolves the conflicts and pushes, without asking first.",
                    state.autoFixConflicts, onAutoFixConflicts,
                )
                SwitchRow(
                    Icons.Rounded.Build, BadgeHue.Red, "Fix failing checks automatically",
                    "When tests, the build or any other CI check fails, Claude fixes it and pushes, without asking first. Up to three tries per task before it asks you.",
                    state.autoFixChecks, onAutoFixChecks,
                )
                SwitchRow(
                    Icons.Rounded.NewReleases, BadgeHue.Amber, "Release when a PR is merged",
                    "When a pull request from this app is merged, publishes a GitHub release with the next patch version, like v1.2.3 to v1.2.4.",
                    state.autoRelease, onAutoRelease,
                )
            }

            SectionHeader("Appearance")
            SettingsCard {
                SettingRow(Icons.Rounded.DarkMode, BadgeHue.Violet, "Theme", state.themeMode.label) { dialog = SettingsDialog.Theme }
                AccentRow(state.accent, onAccent)
            }

            SectionHeader("Spending")
            SettingsCard {
                SettingRow(
                    Icons.Rounded.Savings, BadgeHue.Green, "Daily limit",
                    "Spent today: ${SpokenStatus.money(state.spentTodayUsd)}" +
                        (state.dailyLimitUsd?.let { " of ${SpokenStatus.money(it)}" } ?: ", no limit"),
                ) { dialog = SettingsDialog.DailyLimit }
            }
            Note("Claude stops when today's total reaches the limit, counted at list price from what each session reports.")

            SyncSection(state.syncEnabled, state.syncStatus, state.busy, onSyncEnabled, onSyncNow)

            SectionHeader("About")
            SettingsCard {
                SettingRow(Icons.Rounded.Info, BadgeHue.Blue, "Cruise Control", "Version ${state.version}")
                SettingRow(
                    Icons.Rounded.SystemUpdate, BadgeHue.Teal, "Check for updates",
                    "Looks for a newer release on GitHub",
                    enabled = !state.busy,
                    onClick = onCheckUpdate,
                )
            }
        }
    }

    when (dialog) {
        SettingsDialog.AnthropicKey -> TextDialog(
            title = "Claude API key",
            help = "Create one at platform.claude.com. Usage is billed to that account.",
            label = "API key",
            secret = true,
            onDismiss = { dialog = null },
        ) { onSaveKeys(it, "") }
        SettingsDialog.GithubToken -> TextDialog(
            title = "GitHub token",
            help = "A fine-grained token with Contents and Pull requests set to read and write, and Checks and Commit statuses set to read, on your repos. For sync across devices, also set Gists to read and write.",
            label = "Token",
            secret = true,
            onDismiss = { dialog = null },
        ) { onSaveKeys("", it) }
        SettingsDialog.PickRepos -> PickReposDialog(
            saved = state.repos,
            load = onLoadGithubRepos,
            onDismiss = { dialog = null },
            onManual = { dialog = SettingsDialog.AddRepo },
            onAdd = onAddRepos,
        )
        SettingsDialog.AddRepo -> TextDialog(
            title = "Add a repository",
            label = "owner/repo",
            keyboard = KeyboardType.Uri,
            confirm = "Add",
            onDismiss = { dialog = null },
        ) { onAddRepo(it) }
        SettingsDialog.DailyLimit -> TextDialog(
            title = "Daily limit",
            help = "In US dollars. Leave blank for no limit.",
            label = "Daily limit",
            initial = state.dailyLimitUsd?.let { "%.2f".format(java.util.Locale.US, it).removeSuffix(".00") }.orEmpty(),
            keyboard = KeyboardType.Decimal,
            allowBlank = true,
            filter = { it.filter { c -> c.isDigit() || c == '.' } },
            onDismiss = { dialog = null },
        ) { onDailyLimit(it.toDoubleOrNull()?.takeIf { v -> v > 0 }) }
        SettingsDialog.Theme -> ChoiceDialog(
            title = "Theme",
            options = ThemeMode.entries,
            selected = state.themeMode,
            label = { it.label },
            onDismiss = { dialog = null },
            onSelect = onThemeMode,
        )
        null -> Unit
    }
}

private enum class SettingsDialog { AnthropicKey, GithubToken, PickRepos, AddRepo, DailyLimit, Theme }

/** Accent colour: a row of round swatches, the chosen one ticked. Each shrinks a little on a narrow phone rather than being cut off. */
@Composable
private fun AccentRow(selected: Accent, onPick: (Accent) -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Palette, BadgeHue.Accent)
            Spacer(Modifier.width(16.dp))
            RowText("Accent color", selected.label, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 60.dp),
        ) {
            Accent.entries.forEach { accent ->
                val on = accent == selected
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = 40.dp)
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .background(if (dark) accent.dark else accent.light)
                        .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(accent) })
                        .semantics { contentDescription = accent.label },
                ) {
                    if (on) Icon(Icons.Rounded.Check, null, tint = if (dark) Color(0xFF1A1714) else Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * Lists the repos the GitHub token can push to, each with a checkbox, so several can be added at once.
 * Repos already saved show ticked and can't be unticked here. "Enter manually" falls back to typing owner/name,
 * for a repo the list doesn't include.
 */
@Composable
private fun PickReposDialog(
    saved: List<RepoRef>,
    load: suspend () -> Result<List<RepoRef>>,
    onDismiss: () -> Unit,
    onManual: () -> Unit,
    onAdd: (List<RepoRef>) -> Unit,
) {
    val loaded by produceState<Result<List<RepoRef>>?>(null) { value = load() }
    val savedNames = saved.map { it.fullName }.toSet()
    val picked = remember { mutableStateListOf<RepoRef>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Add repositories") },
        text = {
            val result = loaded
            when {
                result == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                result.isFailure -> Text(
                    "Couldn't load your repos. ${dev.aiauto.core.Failures.describe(result.exceptionOrNull()!!)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                result.getOrThrow().isEmpty() -> Text(
                    "No repos found that this token can push to.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(result.getOrThrow(), key = { it.fullName }) { repo ->
                        val already = repo.fullName in savedNames
                        val on = already || repo in picked
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .toggleable(value = on, enabled = !already, role = Role.Checkbox, onValueChange = {
                                    if (it) picked.add(repo) else picked.remove(repo)
                                })
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = null, enabled = !already)
                            Spacer(Modifier.width(12.dp))
                            RowText(repo.fullName, if (already) "Already added" else null, Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(picked.toList()); onDismiss() },
                enabled = picked.isNotEmpty(),
            ) { Text(if (picked.size > 1) "Add ${picked.size}" else "Add") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onManual) { Text("Enter manually") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
