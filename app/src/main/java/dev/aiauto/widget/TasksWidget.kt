package dev.aiauto.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import dev.aiauto.MainActivity
import dev.aiauto.R
import dev.aiauto.car.TasksScreen
import dev.aiauto.core.SpokenStatus
import dev.aiauto.core.Task
import dev.aiauto.core.TaskStatus
import dev.aiauto.core.needsYouFirst
import dev.aiauto.graph

/** Home-screen widget: what Claude is working on, what's waiting on you, and today's spend. Tap to open the app. */
class TasksWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    companion object {
        /** Redraws every placed widget. Cheap, and does nothing when none is on the home screen. */
        fun refresh(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, TasksWidget::class.java))
                if (ids.isNotEmpty()) render(context, manager, ids)
            }.onFailure { Log.w("AiAuto", "Couldn't update the widget", it) }
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val g = context.graph
            val tasks = g.tasks.all().filter { !it.archived }
            val going = tasks.filter { g.engine.isRunning(it.id) || it.isActive || it.status == TaskStatus.NeedsInput }.needsYouFirst()
            val working = going.count { it.status != TaskStatus.NeedsInput }
            val waiting = going.size - working
            val spent = g.settings.spentTodayUsd
            val limit = g.settings.dailyLimitUsd
            val spentThisMonth = g.settings.spentThisMonthUsd
            val monthlyLimit = g.settings.monthlyLimitUsd

            val views = RemoteViews(context.packageName, R.layout.widget_tasks)
            views.setTextViewText(
                R.id.widget_spend,
                "Today ${SpokenStatus.money(spent)}" + (limit?.let { " of ${SpokenStatus.money(it)}" } ?: "") +
                    " · Month ${SpokenStatus.money(spentThisMonth)}" + (monthlyLimit?.let { " of ${SpokenStatus.money(it)}" } ?: ""),
            )
            views.setTextViewText(R.id.widget_summary, summary(working, waiting))
            val rows = listOf(R.id.widget_task1, R.id.widget_task2, R.id.widget_task3)
            rows.forEachIndexed { i, id ->
                val task = going.getOrNull(i)
                if (task == null) {
                    views.setViewVisibility(id, View.GONE)
                } else {
                    views.setViewVisibility(id, View.VISIBLE)
                    views.setTextViewText(id, line(task))
                }
            }
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_mic,
                PendingIntent.getActivity(
                    context, 1,
                    Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_WIDGET_MIC)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            manager.updateAppWidget(ids, views)
        }

        private fun summary(working: Int, waiting: Int): String = when {
            working == 0 && waiting == 0 -> "Nothing running"
            waiting == 0 -> "$working running"
            working == 0 -> "$waiting waiting on you"
            else -> "$working running · $waiting waiting on you"
        }

        private fun line(task: Task): String = "${task.repo.name} · ${task.title} · ${TasksScreen.statusLabel(task)}"
    }
}
