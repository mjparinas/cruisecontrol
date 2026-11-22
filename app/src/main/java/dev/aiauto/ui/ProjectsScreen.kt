package dev.aiauto.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.aiauto.core.Checks
import dev.aiauto.core.ChecksSummary
import dev.aiauto.core.PullStatus
import dev.aiauto.core.RepoRef
import dev.aiauto.core.RepoView
import dev.aiauto.core.SpokenStatus

/** Each repo's main branch, latest change and open pull requests, straight from GitHub. */
@Composable
fun ProjectsScreen(
    repos: List<RepoRef>,
    views: Map<String, RepoView>,
    message: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onMerge: (RepoRef, Int) -> Unit,
) {
    var confirm by remember { mutableStateOf<Pair<RepoRef, PullStatus>?>(null) }
    confirm?.let { (repo, p) ->
        ConfirmMergeDialog(p.pull.number, p.checks, onConfirm = { onMerge(repo, p.pull.number) }, onDismiss = { confirm = null })
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScreenTopBar("Projects", onBack) {
                IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Refresh") }
            }
        },
    ) { padding ->
        if (repos.isEmpty()) {
            EmptyState(
                Icons.Rounded.FolderOpen, BadgeHue.Blue, "No repositories yet",
                "Add a repo in Settings to see its branch, checks and pull requests here.",
                Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            message?.let {
                item {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                    )
                }
            }
            items(repos, key = { it.fullName }) { repo ->
                RepoSection(repo, views[repo.fullName] ?: RepoView(loading = true)) { confirm = repo to it }
            }
        }
    }
}

/** One repo: a small capital header, then a card with its main branch and a row per open pull request. */
@Composable
private fun RepoSection(repo: RepoRef, view: RepoView, onMerge: (PullStatus) -> Unit) {
    val context = LocalContext.current
    val open = { url: String -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    SectionHeader(repo.fullName)
    SettingsCard {
        if (view.loading) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        view.error?.let { SettingRow(Icons.Rounded.ErrorOutline, BadgeHue.Red, "Couldn't load", it) }
        val status = view.status ?: return@SettingsCard
        StatusRow(
            Icons.Rounded.AccountTree,
            checksHue(status.branchChecks),
            repo.defaultBranch,
            status.branchChecks,
            status.latest?.let { c -> c.message + (c.timeMillis?.let { " · " + SpokenStatus.ago(System.currentTimeMillis() - it) } ?: "") },
            onClick = { open(repo.url) },
        )
        if (status.pulls.isEmpty()) {
            SettingRow(Icons.Rounded.DoneAll, BadgeHue.Green, "No open pull requests")
        }
        status.pulls.forEach { p ->
            StatusRow(
                Icons.Rounded.Code,
                checksHue(p.checks),
                "#${p.pull.number} ${p.pull.title}",
                p.checks,
                listOfNotNull("Draft".takeIf { p.pull.draft }, "Auto-merge on".takeIf { p.pull.hasGitHubAutoMerge }).joinToString(" · ").ifBlank { null },
                onClick = { open(p.pull.htmlUrl) },
            ) {
                if (!p.pull.draft) TextButton(onClick = { onMerge(p) }) { Text("Merge") }
            }
        }
    }
}

/** A row with a badge coloured by its checks, a title, the checks in words, and an optional line under them. */
@Composable
private fun StatusRow(
    icon: ImageVector,
    hue: BadgeHue,
    title: String,
    checks: ChecksSummary?,
    detail: String?,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, hue)
        Spacer(Modifier.width(16.dp))
        Column(
            Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClickLabel = "Open $title", onClick = onClick),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            ChecksLabel(checks)
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

private fun checksHue(summary: ChecksSummary?): BadgeHue = when (summary?.state) {
    Checks.Passing -> BadgeHue.Green
    Checks.Failing -> BadgeHue.Red
    Checks.Pending -> BadgeHue.Amber
    else -> BadgeHue.Blue
}
