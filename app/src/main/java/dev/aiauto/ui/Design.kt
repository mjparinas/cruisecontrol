package dev.aiauto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The app's shared look, first built for Settings after Melvin's Tuned app: small capital section headers over rounded
// cards, rows with a tinted round icon badge, whole-row switches, and dialogs for anything typed or picked. Every
// screen builds from these, so a new one matches without restyling.

/** Small capitals over each card, lined up with the text on the card below it. A heading for screen readers. */
@Composable
internal fun SectionHeader(text: String, inset: Dp = 32.dp) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge.merge(TextStyle(fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp)),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = inset)
            .padding(top = 20.dp, bottom = 8.dp)
            .semantics {
                heading()
                contentDescription = text
            },
    )
}

/** One section's rows together on a rounded card; each row's press ripple stays inside the card's corners. */
@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 6.dp),
        content = content,
    )
}

/** A line of small print under a card. */
@Composable
internal fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
    )
}

@Composable
internal fun SettingRow(
    icon: ImageVector,
    hue: BadgeHue,
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, hue)
        Spacer(Modifier.width(16.dp))
        RowText(title, summary, Modifier.weight(1f))
    }
}

@Composable
internal fun SwitchRow(
    icon: ImageVector,
    hue: BadgeHue,
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // Toggleable, so a screen reader says "on" or "off", and the whole row flips the switch.
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, hue)
        Spacer(Modifier.width(16.dp))
        RowText(title, summary, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun RowText(title: String, summary: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (!summary.isNullOrBlank()) {
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The colours of a row's round icon badge. Each has a deep shade for the light theme and a soft one for the dark theme,
 * so the icon reads well on its own tinted circle either way. [Accent] follows the chosen accent colour.
 */
internal enum class BadgeHue(val light: Long, val dark: Long) {
    Blue(0xFF14509A, 0xFF8AB4F8),
    Violet(0xFF5235C8, 0xFFB9A6FF),
    Teal(0xFF005D52, 0xFF4DD0C2),
    Red(0xFF9E1B1B, 0xFFFF8A80),
    Amber(0xFF7A4700, 0xFFFFC266),
    Green(0xFF1B5E20, 0xFF81C995),
    Accent(0, 0),
}

@Composable
internal fun IconBadge(icon: ImageVector, hue: BadgeHue, size: Dp = 44.dp) {
    val colour = hue.colour()
    Box(
        Modifier.size(size).background(colour.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(size / 2))
    }
}

/** A hue's shade for the current theme: deep on light, soft on dark. [BadgeHue.Accent] is the chosen accent. */
@Composable
internal fun BadgeHue.colour(): Color = when {
    this == BadgeHue.Accent -> MaterialTheme.colorScheme.primary
    MaterialTheme.colorScheme.background.luminance() < 0.5f -> Color(dark)
    else -> Color(light)
}

/** The top bar every screen uses: a back arrow when there is somewhere to go back to, the title, then [actions]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScreenTopBar(
    title: String,
    onBack: (() -> Unit)?,
    large: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
        title = {
            Text(
                title,
                style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** A rounded card standing on its own, such as a task in the list. The whole card is tappable when [onClick] is set, and [onClickLabel] says what a tap does. */
@Composable
internal fun RoundCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            // The label finishes TalkBack's "Double tap to …", so say what a tap does.
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .padding(16.dp),
        content = content,
    )
}

internal val CardShape = RoundedCornerShape(24.dp)

/** A screen with nothing to show yet: a large badge, a title, and a line on what to do. */
@Composable
internal fun EmptyState(icon: ImageVector, hue: BadgeHue, title: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon, hue, size = 72.dp)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A dialog with one text field. Saving with a blank field is allowed only when [allowBlank] (it means "clear"). */
@Composable
internal fun TextDialog(
    title: String,
    label: String,
    help: String? = null,
    initial: String = "",
    secret: Boolean = false,
    keyboard: KeyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
    confirm: String = "Save",
    allowBlank: Boolean = false,
    filter: (String) -> String = { it },
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (help != null) {
                    Text(help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = filter(it) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    singleLine = true,
                    label = { Text(label) },
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text.trim()); onDismiss() },
                enabled = allowBlank || text.isNotBlank(),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A setting with a fixed set of choices, picked from a list of radio buttons. */
@Composable
internal fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title) },
        text = {
            // Scrolls, so a long list such as the phone's voices still fits on screen.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .selectable(selected = option == selected, role = Role.RadioButton, onClick = {
                                onSelect(option)
                                onDismiss()
                            })
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
