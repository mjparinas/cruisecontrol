package dev.aiauto.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import dev.aiauto.MainActivity
import dev.aiauto.R
import dev.aiauto.core.AlertKind
import dev.aiauto.core.CarChat
import dev.aiauto.core.Checks
import dev.aiauto.core.PrState
import dev.aiauto.core.RepoRef
import dev.aiauto.core.Speaker as Who
import dev.aiauto.core.Task
import dev.aiauto.core.TaskMessage
import dev.aiauto.core.SpokenStatus
import dev.aiauto.data.Settings

/**
 * Posts each task as a messaging conversation. Android Auto reads these aloud and lets the
 * driver reply by voice; a reply becomes the next prompt for that task.
 */
class Notifier(private val context: Context, private val settings: Settings, private val speaker: Speaker) {
    private val manager = NotificationManagerCompat.from(context)
    private val me = Person.Builder().setName("You").setKey("me").build()
    private val claude = Person.Builder()
        .setName("Claude")
        .setKey("claude")
        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_glyph))
        .build()
    private val pendingNewTasks = mutableMapOf<String, Pair<String, Long>>()

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_TASKS, "Task updates", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_WORKING, "Claude is working", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Failed checks, questions from Claude, and tasks that look stuck. Shown even in quiet mode."
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_PARKED, "Unfinished work", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A reminder when you park with tasks that are still active or have an open pull request."
            },
        )
    }

    fun onMessage(task: Task, message: TaskMessage) {
        if (message.speaker == Who.You) return
        post(task)
        if (settings.readAloudInCar && speaker.inCar) speaker.speak(message.text)
    }

    private var lastProgress: String? = null
    private var lastProgressMillis = 0L

    /** Reads short progress notes like "Building or testing" aloud, without repeating them too often. */
    @Synchronized
    fun onProgress(text: String) {
        if (!settings.readAloudInCar || !speaker.inCar) return
        val now = System.currentTimeMillis()
        if (text == lastProgress || now - lastProgressMillis < PROGRESS_GAP_MILLIS) return
        lastProgress = text
        lastProgressMillis = now
        speaker.speak(text)
    }

    /** Shows (or refreshes) the task's conversation so it pops up on the car screen. */
    fun post(task: Task) {
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("${task.repo.name}: ${task.title}")
            .setGroupConversation(false)
        task.messages.takeLast(MAX_MESSAGES).forEach { m ->
            style.addMessage(m.text, m.timeMillis, if (m.speaker == Who.You) me else claude)
        }
        notify(task.id.hashCode(), style, replyTo = task.id, task = task)
    }

    /**
     * Something the driver should hear about now: failed checks, a question from Claude, or a stuck task. It replaces
     * the task's conversation with a titled alert that Android Auto reads out, and a voice reply goes straight to the
     * task, so "yes" fixes the checks and "stop" stops a stuck task. Quiet mode doesn't hold alerts back.
     */
    fun alert(task: Task, kind: AlertKind, text: String) {
        val title = when (kind) {
            AlertKind.ChecksFailed -> "Checks failed"
            AlertKind.Question -> "Claude needs your answer"
            AlertKind.Stuck -> "Task looks stuck"
            AlertKind.ReviewComment -> "New pull request comment"
        }
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("$title: ${task.repo.name}")
            .setGroupConversation(false)
        task.messages.takeLast(MAX_MESSAGES - 1).filter { it.text != text }.forEach { m ->
            style.addMessage(m.text, m.timeMillis, if (m.speaker == Who.You) me else claude)
        }
        style.addMessage(text, System.currentTimeMillis(), claude)
        val quick = when (kind) {
            AlertKind.ChecksFailed -> "Fix it" to "yes"
            AlertKind.Stuck -> "Stop" to "stop"
            AlertKind.Question -> null
            AlertKind.ReviewComment -> null
        }
        notify(task.id.hashCode(), style, replyTo = task.id, channel = CH_ALERTS, quick = quick, onlyAlertOnce = kind == AlertKind.Question, task = task)
        // A question was already read out as Claude's own message; the other alerts are new lines.
        if (kind != AlertKind.Question && settings.readAloudInCar && speaker.inCar) speaker.speak(text)
    }

    /** Says and shows that spending is close to a limit. Like other alerts, quiet mode doesn't hold it back. */
    fun spendWarning(text: String) {
        if (settings.readAloudInCar && speaker.inCar) speaker.speak(text)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val notification = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_glyph)
            .setContentTitle("Spending limit nearly reached")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(null))
            .build()
        runCatching { manager.notify(SPEND_ID, notification) }
    }

    /** Opens a fresh conversation asking what to do in [repo]. Replying by voice starts a task. */
    @Synchronized
    fun askForNewTask(repo: RepoRef) {
        pendingNewTasks.remove(repo.fullName)
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("New task: ${repo.name}")
            .addMessage("What should I work on in ${repo.name}? Reply with your request.", System.currentTimeMillis(), claude)
        notify(newTaskId(repo), style, replyTo = NEW_PREFIX + repo.fullName)
    }

    @Synchronized
    fun confirmNewTask(repo: RepoRef, request: String) {
        pendingNewTasks[repo.fullName] = request to System.currentTimeMillis()
        postNewTaskMessage(
            repo,
            "I heard: ${SpokenStatus.clean(request)} in ${repo.name}. Reply yes to start, or no to cancel.",
        )
    }

    @Synchronized
    fun pendingNewTask(repo: RepoRef): String? = pendingNewTasks[repo.fullName]?.first

    @Synchronized
    fun expireNewTaskConfirmation(repo: RepoRef): Boolean {
        val pending = pendingNewTasks[repo.fullName] ?: return false
        if (System.currentTimeMillis() - pending.second <= NEW_TASK_CONFIRMATION_MILLIS) return false
        pendingNewTasks.remove(repo.fullName)
        postNewTaskMessage(repo, "That confirmation expired. Reply with your request again.")
        return true
    }

    @Synchronized
    fun answerNewTaskConfirmation(repo: RepoRef, message: String, keepPending: Boolean = false) {
        if (!keepPending) pendingNewTasks.remove(repo.fullName)
        postNewTaskMessage(repo, message)
    }

    private fun postNewTaskMessage(repo: RepoRef, message: String) {
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("New task: ${repo.name}")
            .addMessage(message, System.currentTimeMillis(), claude)
        notify(newTaskId(repo), style, replyTo = NEW_PREFIX + repo.fullName)
    }

    private val chat = ArrayDeque<Triple<Boolean, String, Long>>()

    /**
     * The "Talk to Claude" conversation for Android Auto's messaging view. Replying to it by voice asks
     * Claude directly, and the answer comes back in the same conversation and is read aloud. This works
     * even when the car only shows Cruise Control as a messaging app, which has no button to start a chat.
     * Dismissing it during a drive puts it back (see [CarChat]).
     */
    @Synchronized
    fun postChat(fromClaude: String? = null, fromYou: String? = null) {
        val now = System.currentTimeMillis()
        fromYou?.let { chat.addLast(Triple(false, it, now)) }
        fromClaude?.let { chat.addLast(Triple(true, it, now)) }
        while (chat.size > MAX_MESSAGES) chat.removeFirst()
        if (chat.isEmpty()) chat.addLast(Triple(true, CarChat.GREETING, now))
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("Talk to Claude")
            .setGroupConversation(false)
        chat.forEach { (isClaude, text, time) -> style.addMessage(text, time, if (isClaude) claude else me) }
        notify(CHAT_ID, style, replyTo = CHAT_TARGET)
    }

    /** Starts the conversation over with a greeting, e.g. each time the phone connects to the car. */
    @Synchronized
    fun startChat() {
        chat.clear()
        postChat(fromClaude = CarChat.GREETING)
    }

    fun clearChat() = manager.cancel(CHAT_ID)

    @Synchronized
    fun clearNewTask(repo: RepoRef) {
        pendingNewTasks.remove(repo.fullName)
        manager.cancel(newTaskId(repo))
    }

    fun clear(taskId: String) = manager.cancel(taskId.hashCode())

    fun workingNotification() = NotificationCompat.Builder(context, CH_WORKING)
        .setSmallIcon(R.drawable.ic_glyph)
        .setContentTitle("Claude is working")
        .setContentText("Updates will arrive as messages.")
        .setOngoing(true)
        .setContentIntent(openApp(null))
        .build()

    /** A single reminder when the car has stopped and tasks or open pull requests still need attention. */
    fun parkedWithUnfinishedWork(tasks: List<Task>) {
        val unfinished = tasks.filter { !it.archived && !it.isFinished }
        if (unfinished.isEmpty()) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val count = unfinished.size
        val notification = NotificationCompat.Builder(context, CH_PARKED)
            .setSmallIcon(R.drawable.ic_glyph)
            .setContentTitle("$count task${if (count == 1) "" else "s"} still need attention")
            .setContentText("Open Cruise Control to check your unfinished work.")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(unfinished.first().id))
            .build()
        runCatching { manager.notify(PARKED_ID, notification) }
    }

    private fun notify(
        id: Int,
        style: NotificationCompat.MessagingStyle,
        replyTo: String,
        channel: String = CH_TASKS,
        /** A one-tap reply for the phone, as its button label and the text it sends. */
        quick: Pair<String, String>? = null,
        /** Don't sound again when this replaces a notification that just sounded. */
        onlyAlertOnce: Boolean = false,
        task: Task? = null,
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return

        val replyIntent = PendingIntent.getBroadcast(
            context, id,
            Intent(context, ReplyReceiver::class.java).setAction(ReplyReceiver.ACTION_REPLY).putExtra(ReplyReceiver.EXTRA_TARGET, replyTo),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val reply = NotificationCompat.Action.Builder(R.drawable.ic_glyph, "Reply", replyIntent)
            .addRemoteInput(RemoteInput.Builder(ReplyReceiver.KEY_TEXT).setLabel("Tell Claude").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val readIntent = PendingIntent.getBroadcast(
            context, id,
            Intent(context, ReplyReceiver::class.java).setAction(ReplyReceiver.ACTION_READ).putExtra(ReplyReceiver.EXTRA_TARGET, replyTo),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val markRead = NotificationCompat.Action.Builder(R.drawable.ic_glyph, "Mark as read", readIntent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        // Android Auto's messaging view has no button to start a chat, so a dismissed chat conversation comes back.
        val dismissed = if (replyTo == CHAT_TARGET) PendingIntent.getBroadcast(
            context, id + 4,
            Intent(context, ReplyReceiver::class.java).setAction(ReplyReceiver.ACTION_DISMISS).putExtra(ReplyReceiver.EXTRA_TARGET, replyTo),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ) else null

        val quickAction = quick?.let { (label, text) ->
            val intent = PendingIntent.getBroadcast(
                context, id + 1,
                Intent(context, ReplyReceiver::class.java).setAction(ReplyReceiver.ACTION_QUICK)
                    .putExtra(ReplyReceiver.EXTRA_TARGET, replyTo).putExtra(ReplyReceiver.EXTRA_QUICK_TEXT, text),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            NotificationCompat.Action.Builder(R.drawable.ic_glyph, label, intent).setShowsUserInterface(false).build()
        }

        val openPull = task?.prUrl?.let { url ->
            val intent = PendingIntent.getActivity(
                context, id + 2, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            NotificationCompat.Action.Builder(R.drawable.ic_glyph, "Open pull request", intent).build()
        }
        val merge = task?.takeIf { it.prUrl != null && it.prState == PrState.Open && it.checks?.state == Checks.Passing }?.let {
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TASK, it.id)
                .putExtra(MainActivity.EXTRA_CONFIRM_MERGE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(context, id + 3, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            NotificationCompat.Action.Builder(R.drawable.ic_glyph, "Merge", pending).build()
        }

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_glyph)
            .setColor(0xFFC96F4A.toInt())
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(onlyAlertOnce)
            .addAction(reply)
            .apply { openPull?.let(::addAction) }
            .apply { merge?.let(::addAction) }
            .apply { quickAction?.let(::addAction) }
            .addInvisibleAction(markRead)
            .apply { dismissed?.let(::setDeleteIntent) }
            .setContentIntent(openApp(replyTo.takeUnless { it.startsWith(NEW_PREFIX) || it == CHAT_TARGET }))
            .build()
        // Notifications can be blocked or throttled; that must never take a running task down with it.
        runCatching { manager.notify(id, notification) }
    }

    private fun openApp(taskId: String?): PendingIntent = PendingIntent.getActivity(
        context, taskId?.hashCode() ?: 0,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TASK, taskId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun newTaskId(repo: RepoRef) = (NEW_PREFIX + repo.fullName).hashCode()

    companion object {
        const val CH_TASKS = "tasks"
        const val CH_WORKING = "working"
        const val CH_ALERTS = "alerts"
        const val CH_PARKED = "parked"
        const val WORKING_ID = 1
        private const val SPEND_ID = 5
        private const val PARKED_ID = 3
        const val NEW_PREFIX = "new:"
        const val CHAT_TARGET = "chat"
        private const val CHAT_ID = 2
        private const val MAX_MESSAGES = 8
        private const val PROGRESS_GAP_MILLIS = 15_000L
        private const val NEW_TASK_CONFIRMATION_MILLIS = 60_000L
    }
}
