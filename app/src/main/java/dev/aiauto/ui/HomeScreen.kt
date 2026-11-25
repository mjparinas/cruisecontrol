package dev.aiauto.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aiauto.core.Checks
import dev.aiauto.core.PrState
import dev.aiauto.core.SpendLevel
import dev.aiauto.core.SpendMeter
import dev.aiauto.core.SpokenStatus
import dev.aiauto.core.Task
import dev.aiauto.core.TaskStatus
import dev.aiauto.core.needsYouFirst
import dev.aiauto.core.runningCount
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    tasks: List<Task>,
    hasRepos: Boolean,
    onOpen: (Task) -> Unit,
    onArchive: (Task) -> Unit,
    onUnarchive: (Task) -> Unit,
    onClear: (Task) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onProjects: () -> Unit,
    onReview: () -> Unit,
    finishedCount: Int,
    onClearFinished: () -> Unit,
    spentToday: Double,
    dailyLimit: Double?,
    spentThisMonth: Double,
    monthlyLimit: Double?,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val showArchived = tab == 1
    val (archived, active) = tasks.partition { it.archived }
    val normalizedQuery = query.trim()
    val shown = if (normalizedQuery.isNotEmpty()) tasks.filter { task ->
        task.title.contains(normalizedQuery, ignoreCase = true) ||
            task.repo.fullName.contains(normalizedQuery, ignoreCase = true) ||
            task.prNumber?.toString()?.contains(normalizedQuery) == true ||
            task.messages.any { it.text.contains(normalizedQuery, ignoreCase = true) }
    }.sortedByDescending { it.updatedMillis } else if (showArchived) archived else active.needsYouFirst()
    val running = tasks.runningCount()
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
    // Clearing can't be undone, so a right swipe asks first.
    var clearing by remember { mutableStateOf<Task?>(null) }
    clearing?.let { task -> ConfirmClearDialog(onConfirm = { onClear(task) }, onDismiss = { clearing = null }) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ScreenTopBar("Tasks", onBack = null, large = true) {
                if (finishedCount > 0 && !showArchived) IconButton(onClick = onClearFinished) { Icon(Icons.Rounded.ClearAll, "Clear $finishedCount finished") }
                IconButton(onClick = onReview) { Icon(Icons.Rounded.Code, "Ready to review") }
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
            SearchField(query, "Search task history") { query = it }
            // A two-way pill switch, as Tuned's segmented choices, rather than tabs with an underline.
            if (normalizedQuery.isEmpty()) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                listOf(if (running == 0) "Active" else "Active ($running)", if (archived.isEmpty()) "Archived" else "Archived (${archived.size})").forEachIndexed { i, label ->
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
                shown.isNotEmpty() -> TaskList(shown, spentToday, dailyLimit, spentThisMonth, monthlyLimit, onOpen, onArchive = archive, onClear = { task -> clearing = task })
                normalizedQuery.isNotEmpty() -> EmptyState(Icons.Rounded.Search, BadgeHue.Blue, "No matching tasks", "Try a request, repo name or pull request number.")
                else -> Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
                    SpendingCard(SpendMeter("Today", spentToday, dailyLimit), SpendMeter("This month", spentThisMonth, monthlyLimit))
                    EmptyState(
                        if (showArchived) Icons.Rounded.Archive else if (hasRepos) Icons.Rounded.DirectionsCar else Icons.Rounded.Key,
                        if (showArchived) BadgeHue.Blue else if (hasRepos) BadgeHue.Accent else BadgeHue.Amber,
                        if (showArchived) "No archived chats" else if (hasRepos) "Nothing running yet" else "Let's get set up",
                        if (showArchived) "Swipe a task card left, or archive it from its chat, to keep it here out of the main list."
                        else if (hasRepos) "Start a task by speaking a request. In the car, pick a repo and reply by voice."
                        else "Add your Claude API key, a GitHub token and a repo in Settings to get started.",
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskList(
    tasks: List<Task>,
    spentToday: Double,
    dailyLimit: Double?,
    spentThisMonth: Double,
    monthlyLimit: Double?,
    onOpen: (Task) -> Unit,
    onArchive: (Task) -> Unit,
    onClear: (Task) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SpendingCard(SpendMeter("Today", spentToday, dailyLimit), SpendMeter("This month", spentThisMonth, monthlyLimit)) }
        items(tasks, key = { it.id }) { task ->
            val swipes = cardSwipes(task.archived)
            SwipeActions(swipes, onArchive = { onArchive(task) }, onClear = { onClear(task) }) {
                TaskCard(task, swipes, onArchive = { onArchive(task) }, onClear = { onClear(task) }) { onOpen(task) }
            }
        }
    }
}

/** Today's and this month's spend side by side on one card, each with a bar towards its limit when one is set. */
@Composable
private fun SpendingCard(today: SpendMeter, month: SpendMeter) {
    RoundCard {
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            SpendColumn(today, Modifier.weight(1f))
            VerticalDivider(Modifier.padding(horizontal = 16.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
            SpendColumn(month, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SpendColumn(meter: SpendMeter, modifier: Modifier) {
    val tint = when (meter.level) {
        SpendLevel.Fine -> MaterialTheme.colorScheme.primary
        SpendLevel.Near -> BadgeHue.Amber.colour()
        SpendLevel.Reached -> MaterialTheme.colorScheme.error
    }
    Column(modifier.clearAndSetSemantics { contentDescription = meter.description }) {
        Text(
            meter.label.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(meter.amount, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        meter.share?.let { share ->
            LinearProgressIndicator(
                progress = { share },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = tint,
                trackColor = tint.copy(alpha = 0.18f),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(meter.note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** What swiping a task card does: left archives it, right clears it. */
internal enum class CardSwipe { Archive, Clear }

/** The swipes a card offers. An archived card can only be cleared, since it's already archived. */
internal fun cardSwipes(archived: Boolean): Set<CardSwipe> =
    if (archived) setOf(CardSwipe.Clear) else setOf(CardSwipe.Archive, CardSwipe.Clear)

@OptIn(ExperimentalMaterial3Api::class)
internal fun cardSwipe(value: SwipeToDismissBoxValue): CardSwipe? = when (value) {
    SwipeToDismissBoxValue.StartToEnd -> CardSwipe.Clear
    SwipeToDismissBoxValue.EndToStart -> CardSwipe.Archive
    SwipeToDismissBoxValue.Settled -> null
}

/** Swiping the card left archives it; swiping right asks to clear it, so the card springs back until you confirm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeActions(swipes: Set<CardSwipe>, onArchive: () -> Unit, onClear: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            when (cardSwipe(it)) {
                CardSwipe.Archive -> { onArchive(); true }
                CardSwipe.Clear -> { onClear(); false }
                null -> false
            }
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = CardSwipe.Clear in swipes,
        enableDismissFromEndToStart = CardSwipe.Archive in swipes,
        backgroundContent = {
            val clear = cardSwipe(state.dismissDirection) == CardSwipe.Clear
            val tint = if (clear) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CardShape)
                    .background(tint.copy(alpha = 0.15f))
                    .padding(horizontal = 24.dp),
                contentAlignment = if (clear) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                // Decorative: the card itself offers its swipe actions to screen readers.
                Icon(if (clear) Icons.Rounded.Delete else Icons.Rounded.Archive, contentDescription = null, tint = tint)
            }
        },
    ) { content() }
}

/** Asks before clearing a task, since it can't be brought back. */
@Composable
private fun ConfirmClearDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear this task?") },
        text = { Text("It leaves the list and its chat is deleted. Its pull request stays on GitHub.") },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TaskCard(task: Task, swipes: Set<CardSwipe>, onArchive: () -> Unit, onClear: () -> Unit, onClick: () -> Unit) {
    val (icon, hue) = task.status.badge(task.interrupted)
    // Swiping is hard or impossible with TalkBack or switch access, so the swipe actions are also in the card's actions menu.
    val actions = Modifier.semantics {
        customActions = swipes.map {
            when (it) {
                CardSwipe.Archive -> CustomAccessibilityAction("Archive") { onArchive(); true }
                CardSwipe.Clear -> CustomAccessibilityAction("Clear") { onClear(); true }
            }
        }
    }
    RoundCard(actions, onClick = onClick, onClickLabel = "open chat") {
        Row(verticalAlignment = Alignment.Top) {
            IconBadge(icon, hue)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        task.repo.name.uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusPill(task)
                }
                Spacer(Modifier.height(4.dp))
                Text(task.title, style = MaterialTheme.typography.titleMedium)
                task.lastMessage?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
