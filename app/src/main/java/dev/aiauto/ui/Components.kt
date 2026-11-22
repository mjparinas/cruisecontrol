package dev.aiauto.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.aiauto.car.VoiceInput
import dev.aiauto.core.Dictation
import dev.aiauto.core.Task
import dev.aiauto.core.TaskStatus

/** A task's status as a small coloured pill. The colours come from [BadgeHue], whose shades read at 4.5:1 on the pill. */
@Composable
fun StatusPill(task: Task, modifier: Modifier = Modifier) {
    val (label, color) = when {
        task.status == TaskStatus.Failed && task.interrupted -> "Offline" to BadgeHue.Amber.colour()
        // Claude is done but the pull request's checks aren't, so it isn't done yet.
        task.checksRunning -> "Checks running" to BadgeHue.Amber.colour()
        else -> when (task.status) {
            TaskStatus.Starting -> "Starting" to MaterialTheme.colorScheme.onSurfaceVariant
            TaskStatus.Working -> "Working" to MaterialTheme.colorScheme.primary
            TaskStatus.NeedsInput -> "Needs you" to BadgeHue.Amber.colour()
            TaskStatus.Done -> "Done" to BadgeHue.Green.colour()
            TaskStatus.Failed -> "Stopped" to MaterialTheme.colorScheme.error
            TaskStatus.Stopped -> "Stopped" to MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Pill(label, color, modifier, showDot = true)
}

@Composable
fun Pill(label: String, color: Color, modifier: Modifier = Modifier, showDot: Boolean = false) {
    val background = color.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surface)
    val textColor = accessibleForeground(color, background)
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showDot) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Keeps small status text at WCAG AA contrast even when a theme or new status shade changes. */
private fun accessibleForeground(preferred: Color, background: Color): Color {
    if (contrastRatio(preferred, background) >= 4.5f) return preferred
    val dark = Color.Black
    val light = Color.White
    return if (contrastRatio(dark, background) >= contrastRatio(light, background)) dark else light
}

private fun contrastRatio(foreground: Color, background: Color): Float {
    val lighter = maxOf(foreground.luminance(), background.luminance())
    val darker = minOf(foreground.luminance(), background.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

/**
 * Insets for a Scaffold bottomBar. The app draws edge to edge, and Scaffold does not pad its bottomBar slot, so the
 * bar must keep itself above the navigation bar and, if a keyboard is open, the keyboard. The union makes sure the
 * two are not added together.
 */
@Composable
fun Modifier.bottomBarInsets(): Modifier =
    this.background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))

/**
 * The phone's only way to ask Claude for something: a floating microphone, with no text box. It listens in the app
 * and turns into a Stop button while listening. With [listenUntilStop] off it sends as soon as the person stops
 * talking; with it on, pauses don't send and Stop does. Stop always sends what was heard so far.
 */
@Composable
fun MicButton(
    enabled: Boolean = true,
    listenUntilStop: Boolean = false,
    disabledReason: String = "",
    autoStart: Boolean = false,
    onAutoStartHandled: () -> Unit = {},
    onSend: (String) -> Unit,
) {
    var listening by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val voice = remember { VoiceInput(context) }
    DisposableEffect(voice) { onDispose { voice.cancel() } }
    val listen = {
        listening = true
        voice.listen(
            onText = { listening = false; onSend(it) },
            onError = { listening = false; Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
            untilStopped = listenUntilStop,
        )
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else Toast.makeText(context, VoiceInput.MIC_PERMISSION, Toast.LENGTH_SHORT).show()
    }
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoStart) {
        if (autoStart && !autoStarted) {
            autoStarted = true
            onAutoStartHandled()
            when {
                !enabled -> Toast.makeText(context, disabledReason, Toast.LENGTH_SHORT).show()
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> listen()
                else -> microphone.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (listening) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    Dictation.listeningHint(listenUntilStop),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        LargeFloatingActionButton(
            onClick = {
                when {
                    listening -> voice.finish()
                    !enabled -> Toast.makeText(context, disabledReason, Toast.LENGTH_SHORT).show()
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> listen()
                    else -> microphone.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            shape = CircleShape,
            containerColor = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            contentColor = if (listening) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(
                if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                contentDescription = if (listening) "Stop and send" else "Speak",
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

/** A small coloured label for a pull request's checks. */
@Composable
fun ChecksLabel(summary: dev.aiauto.core.ChecksSummary?, modifier: Modifier = Modifier) {
    val color = when (summary?.state) {
        dev.aiauto.core.Checks.Passing -> BadgeHue.Green.colour()
        dev.aiauto.core.Checks.Failing -> MaterialTheme.colorScheme.error
        dev.aiauto.core.Checks.Pending -> BadgeHue.Amber.colour()
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val textColor = accessibleForeground(color, MaterialTheme.colorScheme.surface)
    Text(
        dev.aiauto.core.SpokenStatus.checks(summary).replaceFirstChar { it.uppercase() },
        style = MaterialTheme.typography.labelMedium,
        color = textColor,
        modifier = modifier,
    )
}

/** Asks before merging, with a warning when checks aren't green. */
@Composable
fun ConfirmMergeDialog(number: Int, checks: dev.aiauto.core.ChecksSummary?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge pull request #$number?") },
        text = {
            Text(
                when (checks?.state) {
                    dev.aiauto.core.Checks.Failing -> "Checks are failing. Merge anyway?"
                    dev.aiauto.core.Checks.Pending -> "Checks are still running. If GitHub requires them, it will merge once they pass."
                    else -> "This merges it into the base branch on GitHub."
                },
            )
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onDismiss(); onConfirm() }) { Text("Merge") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
