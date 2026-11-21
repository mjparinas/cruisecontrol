package dev.aiauto.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aiauto.core.Task
import dev.aiauto.core.TaskStatus

@Composable
fun StatusPill(task: Task, modifier: Modifier = Modifier) {
    val (label, color) = when {
        task.status == TaskStatus.Failed && task.interrupted -> "Offline" to Color(0xFFB7791F)
        // Claude is done but the pull request's checks aren't, so it isn't done yet.
        task.checksRunning -> "Checks running" to Color(0xFFD9822B)
        else -> when (task.status) {
            TaskStatus.Starting -> "Starting" to MaterialTheme.colorScheme.onSurfaceVariant
            TaskStatus.Working -> "Working" to MaterialTheme.colorScheme.primary
            TaskStatus.NeedsInput -> "Needs you" to Color(0xFFB7791F)
            TaskStatus.Done -> "Done" to Color(0xFF4F7A55)
            TaskStatus.Failed -> "Stopped" to MaterialTheme.colorScheme.error
            TaskStatus.Stopped -> "Stopped" to MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Pill(label, color, modifier, showDot = true)
}

@Composable
fun Pill(label: String, color: Color, modifier: Modifier = Modifier, showDot: Boolean = false) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
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
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Insets for a Scaffold bottomBar that holds a text input. The app draws edge to edge, and Scaffold does not
 * pad its bottomBar slot, so the bar must keep itself above the navigation bar and, while typing, the keyboard.
 * The union makes sure the two are not added together.
 */
@Composable
fun Modifier.bottomBarInsets(): Modifier =
    this.background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))

/**
 * A prompt box with a send button and a mic button that uses the phone's speech recognizer.
 * Spoken input is sent right away, together with anything already typed.
 */
@Composable
fun PromptBar(placeholder: String, enabled: Boolean = true, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                onSend((if (text.isBlank()) spoken else "$text $spoken").trim())
                text = ""
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(placeholder) },
                // A pill on the card colour with no outline until focused, like Tuned's search box.
                shape = RoundedCornerShape(28.dp),
                maxLines = 5,
                enabled = enabled,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                ),
                trailingIcon = {
                    IconButton(enabled = enabled, onClick = {
                        voice.launch(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, placeholder),
                        )
                    }) { Icon(Icons.Rounded.Mic, contentDescription = "Speak") }
                },
            )
            FilledIconButton(
                enabled = enabled && text.isNotBlank(),
                onClick = { onSend(text.trim()); text = "" },
            ) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send") }
        }
    }
}

/** A small colored label for a pull request's checks. */
@Composable
fun ChecksLabel(summary: dev.aiauto.core.ChecksSummary?, modifier: Modifier = Modifier) {
    val color = when (summary?.state) {
        dev.aiauto.core.Checks.Passing -> Color(0xFF4F7A55)
        dev.aiauto.core.Checks.Failing -> MaterialTheme.colorScheme.error
        dev.aiauto.core.Checks.Pending -> Color(0xFFB7791F)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        dev.aiauto.core.SpokenStatus.checks(summary).replaceFirstChar { it.uppercase() },
        style = MaterialTheme.typography.labelMedium,
        color = color,
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
