package dev.aiauto.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.aiauto.core.RepoView
import dev.aiauto.core.SpokenStatus
import dev.aiauto.graph
import kotlinx.coroutines.launch

/** Every registered repo with its main branch checks and open pull request count. */
class ProjectsScreen(carContext: CarContext) : Screen(carContext) {
    private val g = carContext.graph

    init {
        lifecycleScope.launch { g.projects.repos.collect { invalidate() } }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { g.projects.refreshAll(g.repos.repos.value, maxAgeMillis = 60_000) }
        })
    }

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder().setNoItemsMessage("Add a repo on your phone first.")
        g.repos.repos.value.take(dev.aiauto.core.RepoHistory.CAR_ROWS).forEach { repo ->
            items.addItem(
                Row.Builder()
                    .setTitle(repo.name)
                    .addText(summary(g.projects.view(repo)))
                    .setOnClickListener { screenManager.push(RepoStatusScreen(carContext, repo)) }
                    .setBrowsable(true)
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setTitle("Projects")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }

    private fun summary(view: RepoView): String {
        val status = view.status ?: return view.error ?: "Loading…"
        val prs = when (status.pulls.size) {
            0 -> "no open PRs"
            1 -> "1 open PR"
            else -> "${status.pulls.size} open PRs"
        }
        return "${status.repo.defaultBranch}: ${SpokenStatus.checks(status.branchChecks)} · $prs"
    }
}
