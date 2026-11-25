package dev.aiauto.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.aiauto.core.RepoHistory
import dev.aiauto.core.SpokenStatus
import dev.aiauto.graph
import kotlinx.coroutines.launch

/**
 * A task's whole conversation, including Claude's earlier messages, newest first. Each row is cut to a glance and
 * tapping it reads the message in full. Read all reads the whole conversation aloud, which is how the driver gets
 * through a long history; scrolling back to older pages only works while parked.
 */
class TaskHistoryScreen(carContext: CarContext, private val taskId: String) : Screen(carContext) {
    private val g = carContext.graph
    private var skip = 0
    private val earlierSkips = ArrayDeque<Int>()
    private val reader = HistoryReader(this)

    init {
        lifecycleScope.launch { g.tasks.tasks.collect { invalidate() } }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                g.engine.refreshHistory(taskId)
            }
        })
    }

    override fun onGetTemplate(): Template {
        val task = g.tasks.get(taskId) ?: g.tasks.cleared().firstOrNull { it.id == taskId }
            ?: return MessageTemplate.Builder("This task was cleared.").setHeader(carHeader("History")).build()

        // One row goes to the repo's history, so the messages share what's left of the car's list.
        val page = RepoHistory.page(RepoHistory.messages(task), RepoHistory.CAR_ROWS - 1, skip)
        val now = System.currentTimeMillis()
        val items = ItemList.Builder().setNoItemsMessage("No messages yet.")
        items.addItem(
            Row.Builder()
                .setTitle("All tasks in ${task.repo.name}")
                .setOnClickListener { screenManager.push(RepoHistoryScreen(carContext, task.repo)) }
                .setBrowsable(true)
                .build(),
        )
        if (page.newer > 0) items.addItem(historyPageRow(this, "Newer messages", page.newer) { skip = earlierSkips.removeLastOrNull() ?: 0 })
        page.items.forEach { m ->
            items.addItem(
                Row.Builder()
                    .setTitle("${RepoHistory.who(m)} · ${SpokenStatus.ago(now - m.timeMillis)}")
                    .addText(SpokenStatus.glance(m.text))
                    .setOnClickListener { g.speaker.speak(m.text) }
                    .build(),
            )
        }
        if (page.older > 0) items.addItem(historyPageRow(this, "Older messages", page.older) { earlierSkips.addLast(skip); skip += page.items.size })

        return ListTemplate.Builder()
            .setHeader(carHeader(task.title, end = listOfNotNull(reader.action { RepoHistory.spokenTask(task) }, clear().takeIf { !task.cleared })))
            .setSingleList(items.build())
            .build()
    }

    private fun clear() = Action.Builder()
        .setTitle("Clear")
        .setOnClickListener {
            screenManager.push(
                ConfirmScreen(carContext, "Clear this task and its history? Its pull request stays on GitHub.", "Clear") {
                    g.engine.clear(taskId)
                    g.notifier.clear(taskId)
                    screenManager.pop()
                },
            )
        }
        .build()
}

/**
 * A row that pages a history screen, but only while parked, so the driver isn't scrolling through text on the move.
 * While driving it says to tap Read all, which reads the rest aloud.
 */
internal fun historyPageRow(screen: Screen, title: String, count: Int, move: () -> Unit): Row = Row.Builder()
    .setTitle(title)
    .addText("$count more. Tap Read all to hear them, or park to scroll.")
    .setOnClickListener {
        if (screen.carContext.graph.engine.isParked) {
            move()
            screen.invalidate()
        } else {
            CarToast.makeText(screen.carContext, "Park to scroll back, or tap Read all", CarToast.LENGTH_LONG).show()
        }
    }
    .build()

/** The Read all action for a history screen: reads every line aloud, and becomes Stop reading until it's done. */
internal class HistoryReader(private val screen: Screen) {
    private val g = screen.carContext.graph
    private var reading = false

    fun action(lines: () -> List<String>): Action = Action.Builder()
        .setTitle(if (reading) "Stop reading" else "Read all")
        .setOnClickListener {
            g.speaker.stop()
            if (reading) {
                reading = false
            } else {
                val all = lines()
                reading = all.isNotEmpty()
                all.forEachIndexed { i, line ->
                    val last = i == all.lastIndex
                    g.speaker.speak(line, onDone = if (last) ({ screen.lifecycleScope.launch { reading = false; screen.invalidate() } }) else null)
                }
            }
            screen.invalidate()
        }
        .build()
}
