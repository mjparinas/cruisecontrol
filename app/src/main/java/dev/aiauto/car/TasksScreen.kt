package dev.aiauto.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.core.graphics.drawable.IconCompat
import dev.aiauto.R
import dev.aiauto.core.SpokenStatus
import dev.aiauto.core.Task
import dev.aiauto.core.TaskStatus
import dev.aiauto.core.needsYouFirst
import dev.aiauto.graph
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The home screen in the car: recent tasks with their status, and a way to start a new one. */
class TasksScreen(carContext: CarContext) : Screen(carContext) {
    private val g = carContext.graph

    init {
        lifecycleScope.launch {
            combine(g.tasks.tasks, g.repos.repos, g.availableRepos.repos) { _, _, _ -> }.collect { invalidate() }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                g.engine.refreshPullRequests()
                g.refreshAvailableRepos()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        // Archived tasks live on the phone's Archived tab, not in the car.
        val all = g.tasks.tasks.value.filter { !it.archived }.needsYouFirst()
        val finished = all.count { it.isFinished && !g.engine.isRunning(it.id) }
        val canTalk = g.isConfigured && g.repoChoices().isNotEmpty()
        val hasTemplates = canTalk && g.templates.templates.value.isNotEmpty()
        // Car lists hold about six rows; keep one each for "Talk to Claude", "Templates" and "Clear finished" when shown.
        val tasks = all.take(MAX_ROWS - (if (canTalk) 1 else 0) - (if (hasTemplates) 1 else 0) - (if (finished > 0) 1 else 0))
        if (canTalk) {
            items.addItem(
                Row.Builder()
                    .setTitle("Talk to Claude")
                    .addText("Tap and say what you want changed")
                    .setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_mic)).build())
                    .setOnClickListener { PickRepoScreen.startNewTask(carContext, screenManager) }
                    .build(),
            )
            if (hasTemplates) {
                items.addItem(
                    Row.Builder()
                        .setTitle("Templates")
                        .addText(g.templates.templates.value.take(3).joinToString(", ") { it.name })
                        .setOnClickListener { screenManager.push(TemplatesScreen(carContext)) }
                        .build(),
                )
            }
        } else if (tasks.isEmpty()) {
            items.setNoItemsMessage(if (g.isConfigured) "Add a repo on your phone first." else "Finish setup on your phone first.")
        }
        tasks.forEach { task ->
            items.addItem(
                Row.Builder()
                    .setTitle(task.title)
                    .addText(CarText.create("${task.repo.name} · ${statusLabel(task)}"))
                    .apply { task.lastMessage?.let { addText(it.text) } }
                    .setOnClickListener { screenManager.push(TaskDetailScreen(carContext, task.id)) }
                    .build(),
            )
        }
        if (finished > 0) {
            items.addItem(
                Row.Builder()
                    .setTitle("Clear $finished finished task${if (finished == 1) "" else "s"}")
                    .addText("Stopped tasks, and done ones with no open pull request")
                    .setOnClickListener {
                        all.filter { it.isFinished && !g.engine.isRunning(it.id) }.forEach { g.notifier.clear(it.id) }
                        val cleared = g.engine.clearFinished()
                        CarToast.makeText(carContext, "Cleared $cleared", CarToast.LENGTH_SHORT).show()
                    }
                    .build(),
            )
        }
        val newTask = Action.Builder()
            .setTitle("New task")
            .setOnClickListener { PickRepoScreen.startNewTask(carContext, screenManager) }
            .build()
        val projects = Action.Builder()
            .setTitle("Projects")
            .setOnClickListener { screenManager.push(ProjectsScreen(carContext)) }
            .build()
        return ListTemplate.Builder()
            .setTitle("Claude tasks")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(items.build())
            .setActionStrip(ActionStrip.Builder().addAction(newTask).addAction(projects).build())
            .build()
    }

    companion object {
        private const val MAX_ROWS = 6

        fun statusLabel(task: Task): String {
            val pr = SpokenStatus.pullRequest(task)
            return when (task.status) {
                TaskStatus.Starting -> "Starting"
                TaskStatus.Working -> "Working"
                TaskStatus.NeedsInput -> "Needs your reply" + (pr?.let { " · $it" } ?: "")
                TaskStatus.Done -> pr ?: "Done"
                TaskStatus.Failed -> if (task.interrupted) "Connection lost" else "Stopped"
                TaskStatus.Stopped -> "Stopped by you"
            }
        }
    }
}
