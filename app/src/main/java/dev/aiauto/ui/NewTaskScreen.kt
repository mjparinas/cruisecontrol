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
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.aiauto.core.RepoRef
import dev.aiauto.core.TaskTemplate

@Composable
fun NewTaskScreen(
    repos: List<RepoRef>,
    lastUsed: RepoRef?,
    templates: List<TaskTemplate>,
    onBack: () -> Unit,
    onStart: (RepoRef, String) -> Unit,
    onAddTemplate: (name: String, prompt: String) -> Unit,
    onDeleteTemplate: (TaskTemplate) -> Unit,
    listenUntilStop: Boolean = false,
) {
    var selected by remember { mutableStateOf(lastUsed?.takeIf { it in repos } ?: repos.firstOrNull()) }
    var adding by remember { mutableStateOf(false) }
    if (adding) AddTemplateDialog(onDismiss = { adding = false }, onSave = onAddTemplate)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenTopBar("New task", onBack) },
        bottomBar = {
            Column(Modifier.bottomBarInsets()) {
                PromptBar(placeholder = "What should Claude do?", enabled = selected != null, listenUntilStop = listenUntilStop) { text ->
                    selected?.let { onStart(it, text) }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SectionHeader("Repository")
            SettingsCard {
                repos.forEach { repo -> RepoChoice(repo, selected = repo == selected) { selected = repo } }
            }

            SectionHeader("Templates")
            SettingsCard {
                templates.forEach { template ->
                    TemplateRow(
                        template,
                        enabled = selected != null,
                        onRun = { selected?.let { onStart(it, template.prompt) } },
                        onDelete = { onDeleteTemplate(template) },
                    )
                }
                SettingRow(Icons.Rounded.Add, BadgeHue.Accent, "Add a template", "Save a request you repeat, to start it in one tap here or in the car.") {
                    adding = true
                }
            }

            SectionHeader("How it works")
            SettingsCard {
                SettingRow(Icons.Rounded.AccountTree, BadgeHue.Violet, "Works on a new branch", "Describe the change in a sentence or two.")
                SettingRow(Icons.Rounded.Science, BadgeHue.Teal, "Runs the tests it finds", "Before it opens the pull request.")
                SettingRow(Icons.Rounded.Visibility, BadgeHue.Green, "Opens a pull request", "For you to review. Updates arrive as messages you can hear in the car.")
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
            .clickable(enabled = enabled, onClickLabel = "Start ${template.name}", onClick = onRun)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(Icons.Rounded.PlayArrow, BadgeHue.Teal)
        Spacer(Modifier.width(16.dp))
        RowText(template.name, if (template.prompt.length > 90) template.prompt.take(88).trimEnd() + "…" else template.prompt, Modifier.weight(1f))
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
