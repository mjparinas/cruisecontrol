package dev.aiauto.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import dev.aiauto.core.RepoHistory
import dev.aiauto.core.RepoRef
import dev.aiauto.graph
import kotlinx.coroutines.launch

/**
 * Every task in one repo on this phone, including archived and cleared history. Tapping a task opens its history,
 * and Read all reads each task with Claude's latest message. Older pages only open while parked.
 */
class RepoHistoryScreen(carContext: CarContext, private val repo: RepoRef) : Screen(carContext) {
    private val g = carContext.graph
    private var skip = 0
    private val earlierSkips = ArrayDeque<Int>()
    private val reader = HistoryReader(this)

    init {
        lifecycleScope.launch { g.tasks.tasks.collect { invalidate() } }
    }

    override fun onGetTemplate(): Template {
        val all = g.tasks.tasks.value + g.tasks.cleared()
        val page = RepoHistory.page(RepoHistory.tasks(all, repo), RepoHistory.CAR_ROWS, skip)
        val items = ItemList.Builder().setNoItemsMessage("No tasks in ${repo.name} on this phone yet.")
        if (page.newer > 0) items.addItem(historyPageRow(this, "Newer tasks", page.newer) { skip = earlierSkips.removeLastOrNull() ?: 0 })
        page.items.forEach { task ->
            items.addItem(
                Row.Builder()
                    .setTitle(task.title)
                    .addText(
                        "${when {
                            task.cleared -> "Cleared"
                            task.archived -> "Archived"
                            else -> TasksScreen.statusLabel(task)
                        }} · ${task.messages.size} messages",
                    )
                    .setOnClickListener { screenManager.push(TaskHistoryScreen(carContext, task.id)) }
                    .setBrowsable(true)
                    .build(),
            )
        }
        if (page.older > 0) items.addItem(historyPageRow(this, "Older tasks", page.older) { earlierSkips.addLast(skip); skip += page.items.size })

        return ListTemplate.Builder()
            .setTitle("${repo.name} history")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .setActionStrip(ActionStrip.Builder().addAction(reader.action { RepoHistory.spokenRepo(g.tasks.tasks.value, repo) }).build())
            .build()
    }
}
