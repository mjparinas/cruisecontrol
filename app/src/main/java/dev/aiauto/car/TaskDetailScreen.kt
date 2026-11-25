package dev.aiauto.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.aiauto.TaskService
import dev.aiauto.core.Speaker
import dev.aiauto.core.SpokenStatus
import dev.aiauto.core.Task
import dev.aiauto.core.TaskStatus
import dev.aiauto.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * One task's latest updates, with buttons to reply by voice, stop it, hear it, see its whole history, or go to its
 * pull request. While the car
 * is parked, a step Claude's safety check flagged shows here with Approve and Deny.
 */
class TaskDetailScreen(carContext: CarContext, private val taskId: String) : Screen(carContext) {
    private val g = carContext.graph

    init {
        lifecycleScope.launch { g.tasks.tasks.collect { invalidate() } }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (g.tasks.get(taskId)?.hasOpenPr == true) lifecycleScope.launch(Dispatchers.IO) { runCatching { g.engine.refreshPullRequest(taskId) } }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val task = g.tasks.get(taskId)
            ?: return MessageTemplate.Builder("This task was cleared.").setTitle("Task").setHeaderAction(Action.BACK).build()

        val pane = Pane.Builder()
        task.approval?.let { step ->
            pane.addRow(Row.Builder().setTitle("Claude wants to run ${step.toolName}").addText(step.detail ?: "Approve or deny this step").build())
        }
        val summary = listOfNotNull(SpokenStatus.pullRequest(task), task.costUsd.takeIf { it > 0 }?.let { "Cost ${SpokenStatus.money(it)}" })
        if (summary.isNotEmpty()) pane.addRow(Row.Builder().setTitle(TasksScreen.statusLabel(task)).addText(summary.joinToString(" · ")).build())
        // Only the latest two lines, each cut to a glance, so there's little to read while driving. Read aloud says more.
        task.messages.filter { it.speaker != Speaker.You }.takeLast(if (task.approval == null) 2 else 1).forEach { m ->
            pane.addRow(Row.Builder().setTitle(if (m.speaker == Speaker.Claude) "Claude" else "Status").addText(SpokenStatus.glance(m.text)).build())
        }
        if (task.messages.none { it.speaker != Speaker.You }) {
            pane.addRow(Row.Builder().setTitle("Waiting for Claude").addText(TasksScreen.statusLabel(task)).build())
        }

        // A pane holds two actions and the header strip two more. Put the likely next step first.
        val working = g.engine.isRunning(taskId) || task.status == TaskStatus.Working || task.status == TaskStatus.Starting
        val failed = task.status == TaskStatus.Failed
        val main = mutableListOf<Action>()
        val strip = mutableListOf<Action>()
        when {
            task.approval != null -> { main += approve(); main += deny(); strip += stop(); strip += readAloud(task) }
            working -> { main += reply(task, primary = true); main += stop(); strip += readAloud(task); strip += history() }
            // The strip only holds two actions, so Clear moves onto the History screen here.
            failed -> { main += tryAgain(); main += reply(task, primary = false); strip += readAloud(task); strip += history() }
            task.hasOpenPr && task.status != TaskStatus.NeedsInput -> { main += pullRequest(task); main += reply(task, primary = false); strip += readAloud(task); strip += history() }
            else -> { main += reply(task, primary = true); main += readAloud(task); strip += history(); strip += clear() }
        }
        main.forEach { pane.addAction(it) }
        return PaneTemplate.Builder(pane.build())
            .setTitle(task.title)
            .setHeaderAction(Action.BACK)
            .setActionStrip(ActionStrip.Builder().apply { strip.forEach { addAction(it) } }.build())
            .build()
    }

    private fun reply(task: Task, primary: Boolean) = Action.Builder()
        .setTitle("Reply")
        .apply { if (primary) setBackgroundColor(CarColor.PRIMARY) }
        .setOnClickListener {
            screenManager.push(
                ListenScreen(
                    carContext,
                    title = "Reply to Claude",
                    fallback = {
                        g.notifier.post(task)
                        CarToast.makeText(carContext, "Open the Cruise Control message and reply by voice", CarToast.LENGTH_LONG).show()
                    },
                ) { text ->
                    g.engine.prompt(taskId, text)
                    TaskService.ensureRunning(carContext)
                },
            )
        }
        .build()

    private fun approve() = Action.Builder()
        .setTitle("Approve")
        .setBackgroundColor(CarColor.PRIMARY)
        .setOnClickListener {
            g.engine.answerApproval(taskId, allow = true)
            CarToast.makeText(carContext, if (g.engine.isParked) "Approved" else "Denied because the car is moving", CarToast.LENGTH_SHORT).show()
        }
        .build()

    private fun deny() = Action.Builder()
        .setTitle("Deny")
        .setOnClickListener {
            g.engine.answerApproval(taskId, allow = false)
            CarToast.makeText(carContext, "Denied", CarToast.LENGTH_SHORT).show()
        }
        .build()

    private fun stop() = Action.Builder()
        .setTitle("Stop")
        .setOnClickListener {
            g.engine.stop(taskId)
            CarToast.makeText(carContext, "Stopped", CarToast.LENGTH_SHORT).show()
        }
        .build()

    private fun tryAgain() = Action.Builder()
        .setTitle("Try again")
        .setBackgroundColor(CarColor.PRIMARY)
        .setOnClickListener {
            g.engine.retry(taskId)
            TaskService.ensureRunning(carContext)
            CarToast.makeText(carContext, "Trying again", CarToast.LENGTH_SHORT).show()
        }
        .build()

    private fun pullRequest(task: Task) = Action.Builder()
        .setTitle("Pull request")
        .setBackgroundColor(CarColor.PRIMARY)
        .setOnClickListener { task.prNumber?.let { screenManager.push(PullScreen(carContext, task.repo, it)) } }
        .build()

    private fun clear() = Action.Builder()
        .setTitle("Clear")
        .setOnClickListener {
            screenManager.push(
                ConfirmScreen(carContext, "Clear this task from the list? Its pull request stays on GitHub.", "Clear") {
                    g.engine.clear(taskId)
                    g.notifier.clear(taskId)
                    screenManager.pop()
                },
            )
        }
        .build()

    private fun history() = Action.Builder()
        .setTitle("History")
        .setOnClickListener { screenManager.push(TaskHistoryScreen(carContext, taskId)) }
        .build()

    private fun readAloud(task: Task) = Action.Builder()
        .setTitle("Read aloud")
        .setOnClickListener {
            val current = g.tasks.get(taskId) ?: task
            // When stopped, the latest status line is the reason, which is what the driver needs to hear.
            val latest = if (current.status == TaskStatus.Failed) current.lastMessage
            else current.messages.lastOrNull { it.speaker == Speaker.Claude } ?: current.lastMessage
            g.speaker.speak("${current.title}. ${TasksScreen.statusLabel(current)}. ${latest?.text.orEmpty()}")
        }
        .build()
}
