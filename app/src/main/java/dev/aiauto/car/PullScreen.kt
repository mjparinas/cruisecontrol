package dev.aiauto.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.aiauto.core.Checks
import dev.aiauto.core.Failures
import dev.aiauto.core.RepoRef
import dev.aiauto.core.SpokenStatus
import dev.aiauto.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One open pull request: its checks, and a button to merge it after a confirmation. */
class PullScreen(carContext: CarContext, private val repo: RepoRef, private val number: Int) : Screen(carContext) {
    private val g = carContext.graph
    private var merging = false

    init {
        lifecycleScope.launch { g.projects.repos.collect { invalidate() } }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { g.projects.refresh(repo, maxAgeMillis = 15_000) }
        })
    }

    override fun onGetTemplate(): Template {
        val view = g.projects.view(repo)
        val status = view.status
        val title = "Pull request $number"
        if (status == null || merging) {
            return if (view.error != null && !merging) {
                MessageTemplate.Builder(view.error!!).setHeader(carHeader(title)).build()
            } else {
                MessageTemplate.Builder(if (merging) "Merging…" else "Loading…").setHeader(carHeader(title)).build()
            }
        }
        val entry = status.pulls.firstOrNull { it.pull.number == number }
            ?: return MessageTemplate.Builder("Pull request $number isn't open anymore. It may have been merged or closed.")
                .setHeader(carHeader(title)).build()
        val pull = entry.pull
        val checks = entry.checks

        val pane = Pane.Builder()
        pane.addRow(Row.Builder().setTitle(pull.title).addText("Into ${repo.defaultBranch}${if (pull.draft) " · Draft" else ""}").build())
        pane.addRow(
            Row.Builder()
                .setTitle(SpokenStatus.checks(checks).replaceFirstChar { it.uppercase() })
                .apply { if (checks.state == Checks.Failing) addText(checks.failing.take(3).joinToString(", ")) }
                .build(),
        )
        if (pull.hasGitHubAutoMerge) pane.addRow(Row.Builder().setTitle("Auto-merge is on").addText("GitHub merges it when required checks pass").build())

        if (!pull.draft) {
            pane.addAction(
                Action.Builder()
                    .setTitle("Merge")
                    .setBackgroundColor(if (checks.state == Checks.Failing) CarColor.DEFAULT else CarColor.PRIMARY)
                    .setOnClickListener { screenManager.push(ConfirmScreen(carContext, confirmText(checks.state), "Merge") { merge() }) }
                    .build(),
            )
        }
        pane.addAction(
            Action.Builder()
                .setTitle("Read aloud")
                .setOnClickListener { g.speaker.speak(SpokenStatus.forPull(pull, checks)) }
                .build(),
        )
        return PaneTemplate.Builder(pane.build()).setHeader(carHeader(title)).build()
    }

    private fun confirmText(state: Checks) = when (state) {
        Checks.Failing -> "Checks are failing. Merge pull request $number into ${repo.defaultBranch} anyway?"
        Checks.Pending -> "Checks are still running. Merge pull request $number now? If GitHub requires them, it will merge once they pass."
        else -> "Merge pull request $number into ${repo.defaultBranch}?"
    }

    private fun merge() {
        merging = true
        invalidate()
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching { g.mergePull(repo, number) }.getOrElse { "Couldn't merge pull request $number. ${Failures.describe(it)}" }
            }
            merging = false
            runCatching {
                CarToast.makeText(carContext, message, CarToast.LENGTH_LONG).show()
                g.speaker.speak(message)
            }
            invalidate()
        }
    }
}
