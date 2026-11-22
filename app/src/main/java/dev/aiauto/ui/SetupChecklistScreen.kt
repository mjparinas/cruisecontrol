package dev.aiauto.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.TipsAndUpdates
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.aiauto.core.RepoRef
import dev.aiauto.core.GitHub
import dev.aiauto.core.Checks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SetupChecklistScreen(
    hasAnthropicKey: Boolean,
    hasGithubToken: Boolean,
    githubToken: String?,
    repos: List<RepoRef>,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val micAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val notificationsAllowed = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val batteryFree = (context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true)
    var repoChecks by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var checkingRepos by remember { mutableStateOf(false) }
    LaunchedEffect(githubToken, repos) {
        if (githubToken == null || repos.isEmpty()) return@LaunchedEffect
        checkingRepos = true
        repoChecks = withContext(Dispatchers.IO) {
            val github = GitHub(githubToken)
            repos.associate { repo ->
                repo.fullName to runCatching {
                    val permitted = github.repo(repo.owner, repo.name)
                    val commit = github.latestCommit(permitted)
                    val checks = commit?.let { github.checks(permitted, it.sha) }
                    when {
                        commit == null -> "Push allowed; no commit to check"
                        checks?.state == Checks.Unknown -> "Push allowed; token cannot read checks"
                        else -> "Push allowed; checks readable"
                    }
                }.getOrElse { "Needs attention: ${it.message ?: "GitHub request failed"}" }
            }
        }
        checkingRepos = false
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenTopBar("Setup checklist", onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionHeader("Cruise Control")
            SettingsCard {
                SettingRow(Icons.Rounded.Key, if (hasAnthropicKey) BadgeHue.Green else BadgeHue.Amber, if (hasAnthropicKey) "Claude key added" else "Add a Claude key", "Needed to run tasks and Ask mode.", onClick = onSettings)
                SettingRow(Icons.Rounded.Key, if (hasGithubToken) BadgeHue.Green else BadgeHue.Amber, if (hasGithubToken) "GitHub token added" else "Add a GitHub token", "Needs permission to push to your repositories and read pull requests.", onClick = onSettings)
                SettingRow(Icons.Rounded.Check, if (repos.isNotEmpty()) BadgeHue.Green else BadgeHue.Amber, if (repos.isNotEmpty()) "${repos.size} repositories saved" else "Add a repository", "The token must be able to push to each repository.", onClick = onSettings)
                if (checkingRepos) CircularProgressIndicator(Modifier.padding(16.dp))
                repos.forEach { repo ->
                    Text("${repo.fullName}: ${repoChecks[repo.fullName] ?: "Checking access…"}", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            SectionHeader("Phone permissions")
            SettingsCard {
                SettingRow(Icons.Rounded.Mic, if (micAllowed) BadgeHue.Green else BadgeHue.Amber, if (micAllowed) "Microphone allowed" else "Allow microphone", "Used for voice requests.", onClick = { openAppDetails(context) })
                SettingRow(Icons.Rounded.Notifications, if (notificationsAllowed) BadgeHue.Green else BadgeHue.Amber, if (notificationsAllowed) "Notifications allowed" else "Allow notifications", "Task updates and car messaging use notifications.", onClick = { openNotifications(context) })
                SettingRow(Icons.Rounded.TipsAndUpdates, if (batteryFree) BadgeHue.Green else BadgeHue.Amber, if (batteryFree) "Background use unrestricted" else "Allow background use", "Helps tasks continue after the screen turns off or the car disconnects.", onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:${context.packageName}")))
                    }.onFailure { openAppDetails(context) }
                })
            }
            SectionHeader("Android Auto")
            SettingsCard {
                SettingRow(Icons.Rounded.TipsAndUpdates, BadgeHue.Blue, "Allow unknown apps", "Android Auto does not let Cruise Control check this. In Android Auto settings, turn on Unknown sources.")
            }
        }
    }
}

private fun openAppDetails(context: android.content.Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
}

private fun openNotifications(context: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    context.startActivity(intent)
}
