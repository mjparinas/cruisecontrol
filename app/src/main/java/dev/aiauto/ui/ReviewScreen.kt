package dev.aiauto.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aiauto.core.GitHub
import dev.aiauto.core.PullFile
import dev.aiauto.core.PrState
import dev.aiauto.core.Speaker
import dev.aiauto.core.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReviewScreen(
    tasks: List<Task>,
    githubToken: String?,
    onBack: () -> Unit,
    onApprove: (Task) -> Unit,
    onMerge: (Task) -> Unit,
) {
    var merging by remember { mutableStateOf<Task?>(null) }
    merging?.let { task -> task.prNumber?.let { ConfirmMergeDialog(it, task.checks, { onMerge(task) }, { merging = null }) } }
    val context = LocalContext.current
    val candidates = remember(tasks) { tasks.filter { it.prNumber != null && it.prState == PrState.Open }.sortedByDescending { it.updatedMillis } }
    var files by remember { mutableStateOf<Map<String, Pair<List<PullFile>?, String?>>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(candidates, githubToken) {
        if (githubToken == null) return@LaunchedEffect
        loading = true
        files = withContext(Dispatchers.IO) {
            val github = GitHub(githubToken)
            candidates.associate { task ->
                task.id to runCatching { github.pullFiles(task.repo, task.prNumber!!) }
                    .fold({ it to null }, { null to (it.message ?: "Couldn't load this diff.") })
            }
        }
        loading = false
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenTopBar("Ready to review", onBack) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                githubToken == null -> item { EmptyState(Icons.Rounded.Code, BadgeHue.Amber, "GitHub token needed", "Add a GitHub token in Settings to load pull request diffs.") }
                candidates.isEmpty() -> item { EmptyState(Icons.Rounded.Check, BadgeHue.Green, "Nothing to review", "Open pull requests from your tasks will show here.") }
                else -> items(candidates, key = { it.id }) { task ->
                    SettingsCard {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(task.title, style = MaterialTheme.typography.titleMedium)
                            Text("${task.repo.fullName} · Pull request #${task.prNumber}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { task.prUrl?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }) { Text("Open on GitHub") }
                                TextButton(onClick = { onApprove(task) }) { Text("Approve") }
                                TextButton(onClick = { merging = task }) { Text("Merge") }
                            }
                            task.messages.filter { it.speaker != Speaker.Status }.takeLast(5).forEach { message ->
                                Text("${if (message.speaker == Speaker.You) "You" else "Claude"}: ${message.text}", style = MaterialTheme.typography.bodySmall)
                            }
                            when {
                                loading && task.id !in files -> CircularProgressIndicator()
                                files[task.id]?.second != null -> Text(files[task.id]!!.second!!, color = MaterialTheme.colorScheme.error)
                                files[task.id]?.first != null -> files[task.id]!!.first!!.forEach { file ->
                                    Text("${file.filename} · +${file.additions} −${file.deletions}", style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(file.patch ?: "GitHub did not include a text diff for this file.", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 32)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
