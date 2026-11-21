package dev.aiauto.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import dev.aiauto.core.Checks
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Code
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.aiauto.core.PrState
import dev.aiauto.core.Speaker
import dev.aiauto.core.SpokenStatus
import dev.aiauto.core.Task
import dev.aiauto.core.TaskMessage
import dev.aiauto.core.TaskStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(
    task: Task,
    running: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onMerge: () -> Unit,
    listenUntilStop: Boolean = false,
    onSend: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var confirmMerge by remember { mutableStateOf(false) }
    if (confirmMerge) task.prNumber?.let { ConfirmMergeDialog(it, task.checks, onConfirm = onMerge, onDismiss = { confirmMerge = false }) }
    val list = rememberLazyListState()
    // The pull request card and cost header sit above the messages, so scroll to the list's real last item.
    LaunchedEffect(task.messages.size) {
        val above = (if (task.prUrl != null) 1 else 0) + (if (task.costUsd > 0) 1 else 0)
        if (task.messages.isNotEmpty()) list.animateScrollToItem(above + task.messages.size - 1)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(task.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // A polite live region, so TalkBack says "Needs you", "Checks running" or "Done" when the status changes.
                            StatusPill(
                                task,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            Text(
                                task.repo.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(chatText(task)))
                        Toast.makeText(context, "Chat copied", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Rounded.ContentCopy, "Copy chat") }
                    if (running) {
                        IconButton(onClick = onStop) { Icon(Icons.Rounded.StopCircle, "Stop") }
                    }
                    if (task.archived) {
                        IconButton(onClick = onUnarchive) { Icon(Icons.Rounded.Unarchive, "Unarchive") }
                    } else {
                        IconButton(onClick = onArchive) { Icon(Icons.Rounded.Archive, "Archive") }
                    }
                },
                // Tall enough for a two-line title plus the status/repo row, and it grows with the phone's font size
                // so large text isn't clipped.
                expandedHeight = 88.dp * LocalDensity.current.fontScale.coerceIn(1f, 2f),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Column(Modifier.bottomBarInsets()) {
                if (task.status == TaskStatus.Working || task.status == TaskStatus.Starting) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                }
                if (task.status == TaskStatus.Failed) StoppedBanner(task, onRetry)
            }
        },
        floatingActionButton = { MicButton(listenUntilStop = listenUntilStop, onSend = onSend) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            state = list,
            // Room at the bottom so the last message can scroll clear of the floating microphone.
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 136.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            task.prUrl?.let { url ->
                item { PrCard(task, onOpen = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, onMerge = { confirmMerge = true }) }
            }
            if (task.costUsd > 0) {
                item { SectionHeader("Cost so far · ${SpokenStatus.money(task.costUsd)}", inset = 16.dp) }
            }
            items(task.messages, key = { it.id }) { Message(it) }
        }
    }
}

/** The whole conversation as plain text, one message per paragraph. */
private fun chatText(task: Task): String = buildString {
    append("${task.title} (${task.repo.fullName})")
    task.prUrl?.let { append("\n").append(it) }
    task.messages.forEach { append("\n\n${it.speaker}: ${it.text}") }
}

/** The task's pull request on its own card: its checks, and Merge while it is open. Tapping the card opens it on GitHub. */
@Composable
private fun PrCard(task: Task, onOpen: () -> Unit, onMerge: () -> Unit) {
    val hue = when {
        task.prState == PrState.Merged -> BadgeHue.Violet
        task.prState == PrState.Closed -> BadgeHue.Blue
        task.checks?.state == Checks.Passing -> BadgeHue.Green
        task.checks?.state == Checks.Failing -> BadgeHue.Red
        task.checks?.state == Checks.Pending -> BadgeHue.Amber
        else -> BadgeHue.Blue
    }
    RoundCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Code, hue)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pull request #${task.prNumber}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                when (task.prState) {
                    PrState.Merged -> Text("Merged", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    PrState.Closed -> Text("Closed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> ChecksLabel(task.checks)
                }
            }
            if (task.prState != PrState.Merged && task.prState != PrState.Closed) {
                TextButton(onClick = onMerge) { Text("Merge") }
            }
        }
    }
}

/** Says why the task stopped and offers to pick it back up. */
@Composable
private fun StoppedBanner(task: Task, onRetry: () -> Unit) {
    val reason = task.messages.lastOrNull { it.speaker == Speaker.Status }?.text
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (task.interrupted) "Connection lost. Claude may still be working." else reason ?: "This task stopped.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun Message(message: TaskMessage) {
    when (message.speaker) {
        // Your messages sit on the right in an accent bubble with one tighter corner, as a chat app's do.
        Speaker.You -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    // Who said it is shown only by the bubble's side and colour, so say it for screen readers.
                    .semantics { contentDescription = "You: ${message.text}" }
                    .padding(start = 48.dp)
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        // Claude's replies read as a card on the page, left aligned.
        Speaker.Claude -> Text(
            message.text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .semantics { contentDescription = "Claude: ${message.text}" }
                .padding(end = 24.dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
        // Progress notes are quiet: a small icon and a line of small print.
        Speaker.Status -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp).size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
