package dev.aiauto.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.ScreenManager
import dev.aiauto.TaskService
import dev.aiauto.core.RepoRef
import dev.aiauto.core.TaskTemplate
import dev.aiauto.graph

/**
 * Picks the repo for a new task from every repo the token can push to, last used first, then listens for the spoken request on the car screen.
 * With a [template], the task starts right away with the template's request instead.
 */
class PickRepoScreen(carContext: CarContext, private val template: TaskTemplate? = null) : Screen(carContext) {
    private val g = carContext.graph

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder().setNoItemsMessage("Add a repo on your phone first.")
        g.repoChoices().take(dev.aiauto.core.RepoHistory.CAR_ROWS).forEach { repo ->
            items.addItem(
                Row.Builder()
                    .setTitle(repo.name)
                    .addText(repo.owner)
                    .setOnClickListener {
                        screenManager.pop()
                        if (template != null) runTemplate(carContext, screenManager, repo, template)
                        else listenForTask(carContext, screenManager, repo)
                    }
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setHeader(carHeader(template?.let { "${it.name} in which repo?" } ?: "Which repo?"))
            .setSingleList(items.build())
            .build()
    }

    companion object {
        /** Starts a new task by voice: straight to listening with one repo, otherwise asks which repo first. */
        fun startNewTask(carContext: CarContext, screenManager: ScreenManager) {
            val repos = carContext.graph.repoChoices()
            if (repos.size == 1) listenForTask(carContext, screenManager, repos.first())
            else screenManager.push(PickRepoScreen(carContext))
        }

        /** Starts [template] as a task: straight away with one repo, otherwise asks which repo first. */
        fun startTemplate(carContext: CarContext, screenManager: ScreenManager, template: TaskTemplate) {
            val repos = carContext.graph.repoChoices()
            if (repos.size == 1) runTemplate(carContext, screenManager, repos.first(), template)
            else screenManager.push(PickRepoScreen(carContext, template))
        }

        private fun runTemplate(carContext: CarContext, screenManager: ScreenManager, repo: RepoRef, template: TaskTemplate) {
            confirmTask(carContext, screenManager, repo, template.prompt, template.name)
        }

        private fun listenForTask(carContext: CarContext, screenManager: ScreenManager, repo: RepoRef) {
            val g = carContext.graph
            screenManager.push(
                ListenScreen(
                    carContext,
                    title = "New task in ${repo.name}",
                    fallback = {
                        g.notifier.askForNewTask(repo)
                        CarToast.makeText(carContext, "Reply to the Cruise Control message with your request", CarToast.LENGTH_LONG).show()
                    },
                ) { text ->
                    confirmTask(carContext, screenManager, repo, text)
                },
            )
        }

        private fun confirmTask(
            carContext: CarContext,
            screenManager: ScreenManager,
            repo: RepoRef,
            request: String,
            label: String? = null,
        ) {
            screenManager.push(
                ConfirmTaskScreen(carContext, repo, request) {
                    val task = carContext.graph.engine.start(repo, request)
                    TaskService.ensureRunning(carContext)
                    label?.let { CarToast.makeText(carContext, "Started $it in ${repo.name}", CarToast.LENGTH_SHORT).show() }
                    screenManager.push(TaskDetailScreen(carContext, task.id))
                },
            )
        }
    }
}
