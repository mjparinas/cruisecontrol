package dev.aiauto.core

import java.util.UUID

/**
 * Records what happens to a task: state changes and the lines of its conversation.
 * Every part of the engine writes through here, so listeners hear about each new line exactly once.
 */
internal class TaskJournal(
    private val store: TaskStore,
    private val clock: () -> Long,
    /** Called for each new line, so the app can notify and read it aloud. */
    private val onMessage: (Task, TaskMessage) -> Unit,
    /** Called for each new progress note, so the app can read it aloud without posting a notification. */
    private val onProgress: (Task, String) -> Unit,
    /** Quiet mode holds back intermediate lines and progress notes; see [EngineConfig.quietMode]. */
    private val quiet: () -> Boolean,
) {
    @Synchronized
    fun update(taskId: String, transform: (Task) -> Task) {
        val task = store.get(taskId) ?: return
        store.put(transform(task).copy(updatedMillis = clock()))
    }

    fun append(taskId: String, speaker: Speaker, text: String, notify: Boolean = true, idPrefix: String = MESSAGE) {
        val message = TaskMessage(idPrefix + UUID.randomUUID().toString().take(8), speaker, text, clock())
        update(taskId) { it.copy(messages = it.messages + message) }
        // A notification or speech problem must never stop the task itself.
        if (notify && speaker != Speaker.You) store.get(taskId)?.let { runCatching { onMessage(it, message) } }
    }

    /** A line from the middle of a turn, held back in quiet mode. */
    fun step(taskId: String, speaker: Speaker, text: String) = append(taskId, speaker, text, notify = !quiet())

    /** In quiet mode, announces Claude's final word for the turn, since its intermediate lines were held back. */
    fun announceTurnResult(taskId: String) {
        if (!quiet()) return
        val task = store.get(taskId) ?: return
        task.messages.takeLastWhile { it.speaker != Speaker.You }.lastOrNull { it.speaker == Speaker.Claude }
            ?.let { runCatching { onMessage(task, it) } }
    }

    /**
     * Reads the task's last line from Claude or the app aloud again, skipping progress notes, even in quiet mode.
     * Returns false when there is nothing to repeat.
     */
    fun repeatLast(taskId: String): Boolean {
        val task = store.get(taskId) ?: return false
        val last = task.messages.lastOrNull { it.speaker != Speaker.You && !it.id.startsWith(PROGRESS) } ?: return false
        runCatching { onMessage(task, last) }
        return true
    }

    /** A status line from the app, read aloud like Claude's own messages. */
    fun status(taskId: String, text: String, notify: Boolean = true) = append(taskId, Speaker.Status, text, notify)

    /** Progress notes replace the previous one so the conversation doesn't fill with "Editing files". */
    fun progress(taskId: String, text: String) {
        val task = store.get(taskId) ?: return
        val last = task.messages.lastOrNull()
        if (last?.speaker == Speaker.Status && last.text == text) return
        if (last?.speaker == Speaker.Status && last.id.startsWith(PROGRESS)) {
            update(taskId) { it.copy(messages = it.messages.dropLast(1)) }
        }
        append(taskId, Speaker.Status, text, notify = false, idPrefix = PROGRESS)
        if (!quiet()) store.get(taskId)?.let { runCatching { onProgress(it, text) } }
    }

    fun fail(taskId: String, reason: String) {
        status(taskId, reason)
        update(taskId) { it.copy(status = TaskStatus.Failed, interrupted = false, interruptedSinceMillis = null) }
    }

    /**
     * Stopped by a connection problem or a busy server. Claude may still be working, so this is resumable.
     * Only the first drop in a run is announced, so automatic retries don't repeat the message every time.
     */
    fun interrupt(taskId: String, e: Throwable) {
        if (store.get(taskId)?.interruptedSinceMillis == null) {
            status(taskId, "${Failures.describe(e)} Claude may still be working. I'll keep trying to reconnect, or tap Try again.")
        }
        update(taskId) { it.copy(status = TaskStatus.Failed, interrupted = true, interruptedSinceMillis = it.interruptedSinceMillis ?: clock()) }
    }

    private companion object {
        const val MESSAGE = "m-"
        const val PROGRESS = "p-"
    }
}
