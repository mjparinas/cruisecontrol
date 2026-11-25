package dev.aiauto.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import dev.aiauto.TaskService
import dev.aiauto.core.CarChat
import dev.aiauto.core.VoiceCommand
import dev.aiauto.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles voice replies, "mark as read" and dismissals from the phone or the car. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A reply that fails to send must not crash the app while someone is driving.
        try {
            handle(context, intent)
        } catch (e: Throwable) {
            Log.e("AiAuto", "Couldn't handle a reply", e)
        }
    }

    private fun handle(context: Context, intent: Intent) {
        val target = intent.getStringExtra(EXTRA_TARGET) ?: return
        val g = context.graph
        if (intent.action == ACTION_READ) {
            if (target == Notifier.CHAT_TARGET) return
            if (target.startsWith(Notifier.NEW_PREFIX)) g.repos.find(target.removePrefix(Notifier.NEW_PREFIX))?.let(g.notifier::clearNewTask)
            else g.notifier.clear(target)
            return
        }
        if (intent.action == ACTION_DISMISS) {
            if (target == Notifier.CHAT_TARGET && CarChat.restoreAfterDismiss(g.speaker.inCar, g.isConfigured)) g.notifier.postChat()
            return
        }
        val text = if (intent.action == ACTION_QUICK) intent.getStringExtra(EXTRA_QUICK_TEXT)
        else RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim()
        if (text.isNullOrEmpty()) return
        if (target == Notifier.CHAT_TARGET) {
            // Show the question right away so the car stops waiting, then answer when Claude replies.
            g.notifier.postChat(fromYou = text)
            val pending = goAsync()
            g.scope.launch(Dispatchers.IO) {
                try {
                    val answer = g.assistant.ask(text)
                    g.notifier.postChat(fromClaude = answer)
                    if (g.speaker.inCar) g.speaker.speak(answer)
                } catch (e: Throwable) {
                    Log.e("AiAuto", "Couldn't answer a chat reply", e)
                    runCatching { g.notifier.postChat(fromClaude = "Sorry, that didn't go through. Try again.") }
                } finally {
                    pending.finish()
                }
            }
            return
        }
        if (target.startsWith(Notifier.NEW_PREFIX)) {
            val repo = g.repos.find(target.removePrefix(Notifier.NEW_PREFIX)) ?: return
            if (g.notifier.expireNewTaskConfirmation(repo)) return
            val pending = g.notifier.pendingNewTask(repo)
            if (pending == null) {
                if (VoiceCommand.confirmation(text) != null) {
                    g.notifier.answerNewTaskConfirmation(repo, "Tell me what you want to work on.")
                } else {
                    g.notifier.confirmNewTask(repo, text)
                }
                return
            }
            when (VoiceCommand.confirmation(text)) {
                VoiceCommand.Yes -> {
                    g.notifier.clearNewTask(repo)
                    val task = g.engine.start(repo, pending)
                    g.notifier.post(task)
                }
                VoiceCommand.No -> g.notifier.answerNewTaskConfirmation(repo, "Okay, I won't start it.")
                else -> g.notifier.answerNewTaskConfirmation(
                    repo,
                    "Reply yes to start the request I read back, or no to cancel.",
                    keepPending = true,
                )
            }
        } else {
            g.engine.prompt(target, text)
            g.tasks.get(target)?.let(g.notifier::post)
        }
        TaskService.ensureRunning(context)
    }

    companion object {
        const val ACTION_REPLY = "dev.aiauto.REPLY"
        const val ACTION_READ = "dev.aiauto.READ"
        /** The driver dismissed a conversation, used to put the "Talk to Claude" chat back during a drive. */
        const val ACTION_DISMISS = "dev.aiauto.DISMISS"
        /** A one-tap reply from an alert, such as "yes" to fix failed checks. */
        const val ACTION_QUICK = "dev.aiauto.QUICK"
        const val EXTRA_QUICK_TEXT = "quick_text"
        const val EXTRA_TARGET = "target"
        const val KEY_TEXT = "text"
    }
}
