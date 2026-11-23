package dev.aiauto.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.aiauto.core.RepoHistory
import dev.aiauto.core.RepoRef
import dev.aiauto.core.SpokenStatus
import dev.aiauto.graph
import kotlinx.coroutines.launch

/** One repo: what landed last on its main branch, whether that's green, its open pull requests and its task history. */
class RepoStatusScreen(carContext: CarContext, private val repo: RepoRef) : Screen(carContext) {
    private val g = carContext.graph

    init {
        lifecycleScope.launch { g.projects.repos.collect { invalidate() } }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { g.projects.refresh(repo, maxAgeMillis = 30_000) }
        })
    }

    override fun onGetTemplate(): Template {
        val view = g.projects.view(repo)
        val status = view.status
        val template = ListTemplate.Builder().setTitle(repo.name).setHeaderAction(Action.BACK)
        if (status == null) {
            if (view.error == null) return template.setLoading(true).build()
            return template.setSingleList(ItemList.Builder().setNoItemsMessage(view.error!!).build())
                .setActionStrip(ActionStrip.Builder().addAction(refresh()).build())
                .build()
        }

        val items = ItemList.Builder()
        val latest = status.latest
        items.addItem(
            Row.Builder()
                .setTitle("${repo.defaultBranch}: ${SpokenStatus.checks(status.branchChecks)}")
                .addText(latest?.let { c -> c.message + (c.timeMillis?.let { " · " + SpokenStatus.ago(System.currentTimeMillis() - it) } ?: "") } ?: "No commits yet")
                .build(),
        )
        // Car lists hold six rows: the branch, up to four pull requests and the history.
        status.pulls.take(4).forEach { p ->
            items.addItem(
                Row.Builder()
                    .setTitle("#${p.pull.number} ${p.pull.title}")
                    .addText((if (p.pull.draft) "Draft · " else "") + SpokenStatus.checks(p.checks).replaceFirstChar { it.uppercase() })
                    .setOnClickListener { screenManager.push(PullScreen(carContext, repo, p.pull.number)) }
                    .setBrowsable(true)
                    .build(),
            )
        }
        if (status.pulls.isEmpty()) items.addItem(Row.Builder().setTitle("No open pull requests").build())
        items.addItem(
            Row.Builder()
                .setTitle("Task history")
                .addText(RepoHistory.tasks(g.tasks.tasks.value, repo).size.let { if (it == 1) "1 task" else "$it tasks" })
                .setOnClickListener { screenManager.push(RepoHistoryScreen(carContext, repo)) }
                .setBrowsable(true)
                .build(),
        )

        val readAloud = Action.Builder()
            .setTitle("Read aloud")
            .setOnClickListener { g.speaker.speak(SpokenStatus.forRepo(status)) }
            .build()
        return template
            .setSingleList(items.build())
            .setActionStrip(ActionStrip.Builder().addAction(readAloud).addAction(refresh()).build())
            .build()
    }

    private fun refresh() = Action.Builder()
        .setTitle("Refresh")
        .setOnClickListener { g.projects.refresh(repo) }
        .build()
}
