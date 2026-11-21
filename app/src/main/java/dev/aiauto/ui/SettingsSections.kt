package dev.aiauto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.aiauto.core.AgentProfile
import dev.aiauto.core.ChatProfile
import dev.aiauto.core.EffortChoice
import dev.aiauto.core.ModelChoice
import dev.aiauto.core.StandingRule
import dev.aiauto.notify.VoiceOption

/** Speeds offered for reading aloud. */
internal val SPEECH_RATES = listOf(0.8f, 1f, 1.2f, 1.4f, 1.6f)

internal fun rateLabel(rate: Float): String = when (rate) {
    1f -> "Normal"
    else -> "%.1fx".format(java.util.Locale.US, rate).replace(".0x", "x")
}

private enum class ClaudeDialog { Model, Effort, ChatModel, ChatEffort, Rules }

/** The "Claude" section: which model and effort coding tasks and the car chat use, web search, and standing rules. */
@Composable
internal fun ClaudeSettingsSection(
    agent: AgentProfile,
    chat: ChatProfile,
    onAgent: (AgentProfile) -> Unit,
    onChat: (ChatProfile) -> Unit,
) {
    var dialog by remember { mutableStateOf<ClaudeDialog?>(null) }
    val ruleCount = agent.rules.count { name -> StandingRule.entries.any { it.name == name } } +
        agent.instructions.lines().count { it.isNotBlank() }

    SectionHeader("Claude")
    SettingsCard {
        SettingRow(Icons.Rounded.SmartToy, BadgeHue.Accent, "Model for tasks", "${agent.modelChoice.label}. ${agent.modelChoice.blurb}") {
            dialog = ClaudeDialog.Model
        }
        SettingRow(Icons.Rounded.Psychology, BadgeHue.Violet, "Effort for tasks", "${agent.effortChoice.label}. ${agent.effortChoice.blurb}") {
            dialog = ClaudeDialog.Effort
        }
        SettingRow(Icons.Rounded.Forum, BadgeHue.Teal, "Model for the car chat", chat.model.label) { dialog = ClaudeDialog.ChatModel }
        SettingRow(Icons.Rounded.Speed, BadgeHue.Blue, "Effort for the car chat", "${chat.effort.label}. ${chat.effort.blurb}") {
            dialog = ClaudeDialog.ChatEffort
        }
        SwitchRow(
            Icons.Rounded.Language, BadgeHue.Green, "Allow web search",
            "Claude can search the web and read pages while it works, for docs or error messages. Its workspace then has open internet access.",
            agent.webSearch,
        ) { onAgent(agent.copy(webSearch = it)) }
        SettingRow(
            Icons.Rounded.Gavel, BadgeHue.Amber, "Standing rules",
            if (ruleCount == 0) "None. Rules Claude follows on every task." else "$ruleCount ${if (ruleCount == 1) "rule" else "rules"} on every task",
        ) { dialog = ClaudeDialog.Rules }
    }
    Note("Changes apply to new tasks. Tasks already running keep the settings they started with.")

    when (dialog) {
        ClaudeDialog.Model -> ChoiceDialog(
            title = "Model for tasks",
            options = ModelChoice.entries,
            selected = agent.modelChoice,
            label = { "${it.label}: ${it.blurb}" },
            onDismiss = { dialog = null },
        ) { onAgent(agent.copy(model = it.id)) }
        ClaudeDialog.Effort -> ChoiceDialog(
            title = "Effort for tasks",
            options = EffortChoice.entries,
            selected = agent.effortChoice,
            label = { "${it.label}: ${it.blurb}" },
            onDismiss = { dialog = null },
        ) { onAgent(agent.copy(effort = it.name)) }
        ClaudeDialog.ChatModel -> ChoiceDialog(
            title = "Model for the car chat",
            options = ModelChoice.entries,
            selected = chat.model,
            label = { "${it.label}: ${it.blurb}" },
            onDismiss = { dialog = null },
        ) { onChat(chat.copy(model = it)) }
        ClaudeDialog.ChatEffort -> ChoiceDialog(
            title = "Effort for the car chat",
            options = EffortChoice.entries,
            selected = chat.effort,
            label = { "${it.label}: ${it.blurb}" },
            onDismiss = { dialog = null },
        ) { onChat(chat.copy(effort = it)) }
        ClaudeDialog.Rules -> RulesDialog(agent, onDismiss = { dialog = null }) { rules, text ->
            onAgent(agent.copy(rules = rules, instructions = text))
        }
        null -> Unit
    }
}

/** Tick common rules and add your own, one per line. */
@Composable
private fun RulesDialog(agent: AgentProfile, onDismiss: () -> Unit, onSave: (List<String>, String) -> Unit) {
    val ticked = remember { mutableStateListOf<String>().apply { addAll(agent.rules) } }
    var text by remember { mutableStateOf(agent.instructions) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Standing rules") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Claude follows these on every task, unless a request says otherwise.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StandingRule.entries.forEach { rule ->
                    val on = rule.name in ticked
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(value = on, role = Role.Checkbox) { if (it) ticked.add(rule.name) else ticked.remove(rule.name) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Text(rule.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = MaterialTheme.shapes.large,
                    minLines = 3,
                    label = { Text("Your own rules, one per line") },
                    placeholder = { Text("Use British spelling") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Keep the order of the list, not the order things were ticked in.
                onSave(StandingRule.entries.map { it.name }.filter { it in ticked }, text.trim())
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private enum class VoiceDialog { Rate, Voice }

/** Speed and voice for reading aloud, shown in the "In the car" card. */
@Composable
internal fun VoiceRows(
    rate: Float,
    voiceName: String?,
    voices: () -> List<VoiceOption>,
    onRate: (Float) -> Unit,
    onVoice: (String?) -> Unit,
    onPreview: () -> Unit,
) {
    var dialog by remember { mutableStateOf<VoiceDialog?>(null) }
    SettingRow(Icons.Rounded.Tune, BadgeHue.Violet, "Speaking speed", rateLabel(rate)) { dialog = VoiceDialog.Rate }
    SettingRow(
        Icons.Rounded.RecordVoiceOver, BadgeHue.Amber, "Voice",
        voices().firstOrNull { it.name == voiceName }?.label ?: "Phone default",
    ) { dialog = VoiceDialog.Voice }

    when (dialog) {
        VoiceDialog.Rate -> ChoiceDialog(
            title = "Speaking speed",
            options = SPEECH_RATES,
            selected = SPEECH_RATES.minBy { kotlin.math.abs(it - rate) },
            label = ::rateLabel,
            onDismiss = { dialog = null },
        ) { onRate(it); onPreview() }
        VoiceDialog.Voice -> {
            val options = listOf<VoiceOption?>(null) + voices()
            ChoiceDialog(
                title = "Voice",
                options = options,
                selected = options.firstOrNull { it?.name == voiceName },
                label = { it?.label ?: "Phone default" },
                onDismiss = { dialog = null },
            ) { onVoice(it?.name); onPreview() }
        }
        null -> Unit
    }
}

/** Hands-free start, listening until Stop, the welcome-back briefing, and memory across drives, in the "In the car" card. */
@Composable
internal fun CarStartRows(
    listenOnOpen: Boolean,
    listenUntilStop: Boolean,
    briefing: Boolean,
    rememberOn: Boolean,
    onListenOnOpen: (Boolean) -> Unit,
    onListenUntilStop: (Boolean) -> Unit,
    onBriefing: (Boolean) -> Unit,
    onRemember: (Boolean) -> Unit,
    onForget: () -> Unit,
) {
    var confirmForget by remember { mutableStateOf(false) }
    SwitchRow(
        Icons.Rounded.Mic, BadgeHue.Red, "Listen when opened",
        "Starts listening as soon as Cruise Control opens in the car, so \"Hey Google, open Cruise Control\" is all it takes.",
        listenOnOpen, onListenOnOpen,
    )
    SwitchRow(
        Icons.Rounded.StopCircle, BadgeHue.Violet, "Keep listening until Stop",
        "Pauses don't send what you said. The car keeps listening until you tap Stop, so you can think mid-sentence.",
        listenUntilStop, onListenUntilStop,
    )
    SwitchRow(
        Icons.Rounded.WbSunny, BadgeHue.Amber, "Briefing when you get in",
        "When the phone connects to the car, reads what happened since your last drive: finished and failed tasks, merges, questions waiting for you and today's spending.",
        briefing, onBriefing,
    )
    SwitchRow(
        Icons.Rounded.History, BadgeHue.Blue, "Remember past drives",
        "The car chat keeps your last 20 exchanges, so you can pick up where you left off.",
        rememberOn, onRemember,
    )
    SettingRow(Icons.Rounded.DeleteSweep, BadgeHue.Red, "Forget past drives", "Clears what the car chat remembers") { confirmForget = true }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("Forget past drives?") },
            text = { Text("The car chat won't remember anything you said before now.") },
            confirmButton = { TextButton(onClick = { onForget(); confirmForget = false }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

/** Sync across devices through a secret gist on your GitHub account. */
@Composable
internal fun SyncSection(enabled: Boolean, status: String, busy: Boolean, onEnabled: (Boolean) -> Unit, onSyncNow: () -> Unit) {
    SectionHeader("Sync")
    SettingsCard {
        SwitchRow(
            Icons.Rounded.CloudSync, BadgeHue.Blue, "Sync across devices",
            "Keeps repos, tasks, settings and the car chat's memory the same on every phone or tablet signed in to the same GitHub account.",
            enabled, onEnabled,
        )
        if (enabled) SettingRow(Icons.Rounded.Sync, BadgeHue.Teal, "Sync now", status, enabled = !busy, onClick = onSyncNow)
    }
    Note(
        "Stored in a secret gist on your GitHub account, so your token needs Gists set to read and write. " +
            "Your API key and token stay on each device. A task keeps running on the device that started it until you reply from another.",
    )
}
