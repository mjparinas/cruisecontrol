package dev.aiauto.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.aiauto.core.RepoRef
import dev.aiauto.core.SpokenStatus
import dev.aiauto.core.TaskTemplate
import dev.aiauto.core.VoiceCommand

private data class PendingTaskStart(val repo: RepoRef, val request: String, val ask: Boolean, val shared: Boolean = false)

@Composable
fun NewTaskScreen(
    repos: List<RepoRef>,
    lastUsed: RepoRef?,
    templates: List<TaskTemplate>,
    onBack: () -> Unit,
    onStart: (RepoRef, String) -> Unit,
    onStartAsk: (RepoRef, String) -> Unit = onStart,
    onAddTemplate: (name: String, prompt: String) -> Unit,
    onDeleteTemplate: (TaskTemplate) -> Unit,
    listenUntilStop: Boolean = false,
    sharedText: String? = null,
    onStartShared: ((RepoRef, String) -> Unit)? = null,
    autoListen: Boolean = false,
    onAutoListenHandled: () -> Unit = {},
    initialRequest: Pair<RepoRef, String>? = null,
    onReadBack: (String, () -> Unit) -> Unit = { _, done -> done() },
    /** Pictures shared from another app, sent with the next task started here. */
    sharedImages: List<dev.aiauto.core.TaskImage> = emptyList(),
    onRemoveImages: () -> Unit = {},
    /** Saves the task to start on its own at the given time. Null hides Later. */
    onStartLater: ((RepoRef, String, Long) -> Unit)? = null,
) {
    var choosingLater by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(lastUsed?.takeIf { it in repos } ?: repos.firstOrNull()) }
    var askMode by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var pendingStart by remember(initialRequest) {
        mutableStateOf(initialRequest?.let { PendingTaskStart(it.first, it.second, ask = false) })
    }
    var waitingForConfirmation by remember(initialRequest) { mutableStateOf(initialRequest != null) }
    var autoConfirmListen by remember(initialRequest) { mutableStateOf(false) }
    fun readBack(start: PendingTaskStart, done: () -> Unit = {}) {
        val request = SpokenStatus.clean(start.request)
        val pictures = if (sharedImages.isNotEmpty() && !start.ask) ", with ${dev.aiauto.core.TaskImages.describe(sharedImages.size)}" else ""
        onReadBack("I heard: $request in ${start.repo.name}$pictures. Say yes to start, or no to cancel.", done)
    }
    fun requestStart(repo: RepoRef, request: String, ask: Boolean = false, shared: Boolean = false) {
        val start = PendingTaskStart(repo, request, ask, shared)
        pendingStart = start
        waitingForConfirmation = true
        readBack(start)
    }
    fun startPending() {
        val start = pendingStart ?: return
        pendingStart = null
        waitingForConfirmation = false
        when {
            start.ask -> onStartAsk(start.repo, start.request)
            start.shared -> onStartShared?.invoke(start.repo, start.request)
            else -> onStart(start.repo, start.request)
        }
    }
    LaunchedEffect(initialRequest) {
        initialRequest?.let { readBack(PendingTaskStart(it.first, it.second, ask = false)) { autoConfirmListen = true } }
    }
    if (adding) AddTemplateDialog(onDismiss = { adding = false }, onSave = onAddTemplate)
    pendingStart?.let { start ->
        AlertDialog(
            onDismissRequest = { pendingStart = null; waitingForConfirmation = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text(if (start.ask) "Ask Claude about this?" else "Start this task?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("In ${start.repo.fullName}")
                    Text(start.request)
                    if (sharedImages.isNotEmpty() && !start.ask) Text("With ${dev.aiauto.core.TaskImages.describe(sharedImages.size)}")
                }
            },
            confirmButton = {
                TextButton(onClick = ::startPending) { Text(if (start.ask) "Ask Claude" else "Start task") }
            },
            dismissButton = {
                Row {
                    if (onStartLater != null && !start.ask) TextButton(onClick = { choosingLater = true }) { Text("Later") }
                    TextButton(onClick = { pendingStart = null; waitingForConfirmation = false }) { Text("Cancel") }
                }
            },
        )
    }
    if (choosingLater) pendingStart?.let { start ->
        val options = remember { dev.aiauto.core.StartLater.options(System.currentTimeMillis()) }
        AlertDialog(
            onDismissRequest = { choosingLater = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("Start later") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "The task waits on this phone and starts on its own. Keep the phone on and online.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    options.forEach { option ->
                        TextButton(
                            onClick = {
                                choosingLater = false
                                pendingStart = null
                                waitingForConfirmation = false
                                onStartLater?.invoke(start.repo, start.request, option.atMillis)
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(option.label, Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingLater = false }) { Text("Back") } },
        )
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenTopBar("New task", onBack) },
        floatingActionButton = {
            MicButton(
                enabled = selected != null,
                listenUntilStop = listenUntilStop,
                disabledReason = "Add a repo in Settings first.",
                autoStart = autoListen || autoConfirmListen,
                onAutoStartHandled = {
                    if (autoConfirmListen) autoConfirmListen = false else onAutoListenHandled()
                },
            ) { text ->
                if (waitingForConfirmation) {
                    when (VoiceCommand.confirmation(text)) {
                        VoiceCommand.Yes -> startPending()
                        VoiceCommand.No -> { pendingStart = null; waitingForConfirmation = false }
                        else -> readBack(checkNotNull(pendingStart)) {
                            autoConfirmListen = true
                        }
                    }
                } else {
                    selected?.let { requestStart(it, text, askMode) }
                }
            }
        },
    ) { padding ->
        // The bottom padding keeps the last card clear of the floating microphone.
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 136.dp)) {
            if (!sharedText.isNullOrBlank() || sharedImages.isNotEmpty()) {
                SectionHeader("Shared from another app")
                SettingsCard {
                    if (!sharedText.isNullOrBlank()) Text(sharedText, Modifier.fillMaxWidth().padding(16.dp))
                    if (sharedImages.isNotEmpty()) SettingRow(
                        Icons.Rounded.Image, BadgeHue.Teal, dev.aiauto.core.TaskImages.describe(sharedImages.size).replaceFirstChar { it.uppercase() },
                        "Sent with the task you start next. Tap to remove.",
                        onClickLabel = "Remove the shared pictures",
                        onClick = onRemoveImages,
                    )
                    if (onStartShared != null && !sharedText.isNullOrBlank()) SettingRow(Icons.Rounded.PlayArrow, BadgeHue.Accent, "Start this request", "Starts a task in the selected repo.") {
                        selected?.let { requestStart(it, sharedText, shared = true) }
                    }
                }
                if (sharedText.isNullOrBlank()) Note("Say what Claude should do with ${if (sharedImages.size == 1) "it" else "them"}.")
            }
            SectionHeader("Repository")
            SettingsCard {
                repos.forEach { repo -> RepoChoice(repo, selected = repo == selected) { selected = repo } }
            }

            SectionHeader("What do you want to do?")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                listOf("Make a change", "Ask about code").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = askMode == (index == 1),
                        onClick = { askMode = index == 1 },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(label) }
                }
            }

            if (!askMode) SectionHeader("Templates")
            if (!askMode) SettingsCard {
                templates.forEach { template ->
                    TemplateRow(
                        template,
                        enabled = selected != null,
                        onRun = { selected?.let { requestStart(it, template.prompt) } },
                        onDelete = { onDeleteTemplate(template) },
                    )
                }
                SettingRow(Icons.Rounded.Add, BadgeHue.Accent, "Add a template", "Save a request you repeat, to start it in one tap here or in the car.") {
                    adding = true
                }
            }

            SectionHeader("How it works")
            SettingsCard {
                if (askMode) {
                    SettingRow(Icons.Rounded.Mic, BadgeHue.Red, "Ask a question", "Say what you want to understand about this repository.")
                    SettingRow(Icons.Rounded.Visibility, BadgeHue.Green, "Read-only answer", "Claude inspects the code and answers aloud. It won't change files or open a pull request.")
                } else {
                    SettingRow(Icons.Rounded.Mic, BadgeHue.Red, "Tap the microphone", "Say the change in a sentence or two. It starts the task when you're done.")
                    SettingRow(Icons.Rounded.AccountTree, BadgeHue.Violet, "Works on a new branch", "Claude makes the change on its own branch.")
                    SettingRow(Icons.Rounded.Science, BadgeHue.Teal, "Runs the tests it finds", "Before it opens the pull request.")
                    SettingRow(Icons.Rounded.Visibility, BadgeHue.Green, "Opens a pull request", "For you to review. Updates arrive as messages you can hear in the car.")
                }
            }
        }
    }
}

/** One repository to pick, as a radio row on the card. */
@Composable
private fun RepoChoice(repo: RepoRef, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(Icons.Rounded.FolderOpen, if (selected) BadgeHue.Accent else BadgeHue.Blue)
        Spacer(Modifier.width(16.dp))
        RowText(repo.name, repo.owner, Modifier.weight(1f))
        RadioButton(selected = selected, onClick = null)
    }
}

/** One saved template: tap to start it in the selected repo. */
@Composable
private fun TemplateRow(template: TaskTemplate, enabled: Boolean, onRun: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(Icons.Rounded.PlayArrow, BadgeHue.Teal)
        Spacer(Modifier.width(16.dp))
        RowText(template.name, template.prompt, Modifier.weight(1f))
        TextButton(onClick = onRun, enabled = enabled) { Text("Start") }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Close, "Delete ${template.name}") }
    }
}

/** Name and request for a new template. */
@Composable
private fun AddTemplateDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text("New template") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name, like Fix lint") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("What should Claude do?") },
                    minLines = 3,
                    maxLines = 6,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, prompt); onDismiss() }, enabled = name.isNotBlank() && prompt.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
