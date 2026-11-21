package dev.aiauto.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aiauto.core.Checks
import dev.aiauto.core.PrState
import dev.aiauto.core.SpokenStatus
import dev.aiauto.core.Task
import dev.aiauto.core.TaskStatus
import dev.aiauto.core.needsYouFirst
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    tasks: List<Task>,
    hasRepos: Boolean,
    onOpen: (Task) -> Unit,
    onArchive: (Task) -> Unit,
    onUnarchive: (Task) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onProjects: () -> Unit,
    finishedCount: Int,
    onClearFinished: () -> Unit,
    spentToday: Double,
    dailyLimit: Double?,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val showArchived = tab == 1
    val (archived, active) = tasks.partition { it.archived }
    val shown = if (showArchived) archived else active.needsYouFirst()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val archive: (Task) -> Unit = { task ->
        onArchive(task)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("Task archived", actionLabel = "Undo", withDismissAction = true)
            if (result == SnackbarResult.ActionPerformed) onUnarchive(task)
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ScreenTopBar("Tasks", onBack = null, large = true) {
                if (finishedCount > 0 && !showArchived) IconButton(onClick = onClearFinished) { Icon(Icons.Rounded.ClearAll, "Clear $finishedCount finished") }
                IconButton(onClick = onProjects) { Icon(Icons.Rounded.FolderOpen, "Projects") }
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = if (hasRepos) onNew else onSettings,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(if (hasRepos) "New task" else "Add a repo") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // A two-way pill switch, as Tuned's segmented choices, rather than tabs with an underline.
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                listOf("Active", if (archived.isEmpty()) "Archived" else "Archived (${archived.size})").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = tab == i,
                        onClick = { tab = i },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            inactiveContainerColor = MaterialTheme.colorScheme.surface,
                            activeBorderColor = MaterialTheme.colorScheme.outline,
                            inactiveBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                    ) { Text(label) }
                }
            }
            when {
                shown.isNotEmpty() -> TaskList(shown, spentToday, dailyLimit, onOpen, onArchive = if (showArchived) null else archive)
                showArchived -> EmptyState(
                    Icons.Rounded.Archive, BadgeHue.Blue, "No archived chats",
                    "Swipe a task card, or archive it from its chat, to keep it here out of the main list.",
                )
                hasRepos -> EmptyState(
                    Icons.Rounded.DirectionsCar, BadgeHue.Accent, "Nothing running yet",
                    "Start a task by typing or speaking a request. In the car, pick a repo and reply by voice.",
                )
                else -> EmptyState(
                    Icons.Rounded.Key, BadgeHue.Amber, "Let's get set up",
                    "Add your Claude API key, a GitHub token and a repo in Settings to get started.",
                )
            }
        }
    }
}

@Composable
private fun TaskList(
    tasks: List<Task>,
    spentToday: Double,
    dailyLimit: Double?,
    onOpen: (Task) -> Unit,
    onArchive: ((Task) -> Unit)?,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            // The list is already inset 16 dp, so the header needs only 16 more to line up with the card text.
            SectionHeader("Today · ${SpokenStatus.money(spentToday)}" + (dailyLimit?.let { " of ${SpokenStatus.money(it)}" } ?: ""), inset = 16.dp)
        }
        items(tasks, key = { it.id }) { task ->
            if (onArchive == null) TaskCard(task) { onOpen(task) }
            else SwipeToArchive(onArchive = { onArchive(task) }) { TaskCard(task) { onOpen(task) } }
        }
    }
}

/** Swiping the card either way archives it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToArchive(onArchive: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it != SwipeToDismissBoxValue.Settled) onArchive()
            it != SwipeToDismissBoxValue.Settled
        },
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val alignment = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CardShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment,
            ) {
                Icon(Icons.Rounded.Archive, contentDescription = "Archive", tint = MaterialTheme.colorScheme.primary)
            }
        },
    ) { content() }
}

@Composable
private fun TaskCard(task: Task, onClick: () -> Unit) {
    val (icon, hue) = task.status.badge(task.interrupted)
    RoundCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            IconBadge(icon, hue)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        task.repo.name.uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusPill(task)
                }
                Spacer(Modifier.height(4.dp))
                Text(task.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                task.lastMessage?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        DateUtils.getRelativeTimeSpanString(task.updatedMillis).toString() +
                            (if (task.costUsd > 0) " · " + SpokenStatus.money(task.costUsd) else ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    task.prNumber?.let {
                        val failing = task.checks?.state == Checks.Failing && task.prState != PrState.Merged
                        Pill(
                            when (task.prState) {
                                PrState.Merged -> "PR #$it merged"
                                PrState.Closed -> "PR #$it closed"
                                else -> "PR #$it · " + SpokenStatus.checks(task.checks)
                            },
                            if (failing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** The icon and colour of a task's round badge, by its status. */
internal fun TaskStatus.badge(interrupted: Boolean): Pair<ImageVector, BadgeHue> = when {
    this == TaskStatus.Failed && interrupted -> Icons.Rounded.CloudOff to BadgeHue.Amber
    this == TaskStatus.Starting -> Icons.Rounded.HourglassTop to BadgeHue.Blue
    this == TaskStatus.Working -> Icons.Rounded.Autorenew to BadgeHue.Accent
    this == TaskStatus.NeedsInput -> Icons.Rounded.QuestionAnswer to BadgeHue.Amber
    this == TaskStatus.Done -> Icons.Rounded.CheckCircle to BadgeHue.Green
    this == TaskStatus.Failed -> Icons.Rounded.ErrorOutline to BadgeHue.Red
    else -> Icons.Rounded.StopCircle to BadgeHue.Violet
}
