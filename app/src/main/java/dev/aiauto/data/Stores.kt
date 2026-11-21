package dev.aiauto.data

import android.content.Context
import dev.aiauto.core.AgentProfile
import dev.aiauto.core.AgentSetup
import dev.aiauto.core.ChatProfile
import dev.aiauto.core.EffortChoice
import dev.aiauto.core.ModelChoice
import dev.aiauto.core.RepoChange
import dev.aiauto.core.RepoRef
import dev.aiauto.core.SyncMerge
import dev.aiauto.core.Task
import dev.aiauto.core.TaskStore
import dev.aiauto.core.TaskTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** A small JSON-file list that publishes changes as a StateFlow. */
private class JsonListFile<T>(private val file: File, private val serializer: KSerializer<T>) {
    val flow = MutableStateFlow(load())

    private fun load(): List<T> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(serializer), file.readText()) }
            .getOrElse {
                // Keep the unreadable file for inspection instead of overwriting it on the next save.
                runCatching { file.renameTo(File(file.parentFile, file.name + ".bad")) }
                emptyList()
            }
    }

    @Synchronized
    fun set(items: List<T>) {
        flow.value = items
        // A full disk shouldn't crash the app or stop a task; the list stays correct in memory.
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(ListSerializer(serializer), items))
            if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true).also { tmp.delete() }
        }
    }
}

class FileTaskStore(context: Context) : TaskStore {
    private val list = JsonListFile(File(context.filesDir, "tasks.json"), Task.serializer())
    /** Ids of tasks cleared here or on another device, so sync doesn't bring them back. */
    private val removed = JsonListFile(File(context.filesDir, "removed_tasks.json"), kotlinx.serialization.serializer<String>())
    val tasks: StateFlow<List<Task>> = list.flow

    /** Cleared task ids, oldest first. */
    fun removedIds(): List<String> = removed.flow.value

    /** Remembers ids cleared on another device, so this one keeps passing them on. */
    @Synchronized
    fun rememberRemoved(ids: List<String>) {
        val merged = (removed.flow.value + ids).distinct().takeLast(dev.aiauto.core.SyncMerge.REMOVED_KEPT)
        if (merged != removed.flow.value) removed.set(merged)
    }

    override fun get(id: String): Task? = list.flow.value.firstOrNull { it.id == id }
    override fun all(): List<Task> = list.flow.value

    @Synchronized
    override fun put(task: Task) {
        val others = list.flow.value.filter { it.id != task.id }
        list.set((others + task).sortedByDescending { it.updatedMillis })
    }

    @Synchronized
    override fun remove(id: String) {
        list.set(list.flow.value.filter { it.id != id })
        rememberRemoved(listOf(id))
    }
}

class RepoStore(context: Context) {
    private val list = JsonListFile(File(context.filesDir, "repos.json"), RepoRef.serializer())
    private val changes = JsonListFile(File(context.filesDir, "repo_changes.json"), RepoChange.serializer())
    val repos: StateFlow<List<RepoRef>> = list.flow

    /** When each repo was last added and removed here or on another device, for sync. */
    fun changes(): List<RepoChange> = changes.flow.value

    @Synchronized
    fun add(repo: RepoRef) {
        list.set(list.flow.value.filter { it.fullName != repo.fullName } + repo)
        note(RepoChange(repo.fullName, addedMillis = System.currentTimeMillis()))
    }

    @Synchronized
    fun remove(repo: RepoRef) {
        list.set(list.flow.value.filter { !it.fullName.equals(repo.fullName, ignoreCase = true) })
        note(RepoChange(repo.fullName, removedMillis = System.currentTimeMillis()))
    }

    /** Takes the repos a sync round merged, keeping anything added or removed here while the round ran. */
    @Synchronized
    fun applySynced(repos: List<RepoRef>, synced: List<RepoChange>) {
        val (merged, mergedChanges) = SyncMerge.mergeRepos(list.flow.value, changes.flow.value, repos, synced)
        if (merged != list.flow.value) list.set(merged)
        if (mergedChanges != changes.flow.value) changes.set(mergedChanges)
    }

    private fun note(change: RepoChange) {
        val (_, merged) = SyncMerge.mergeRepos(emptyList(), changes.flow.value, emptyList(), listOf(change))
        changes.set(merged)
    }

    @Synchronized

    fun find(fullName: String) = list.flow.value.firstOrNull { it.fullName == fullName }
}

/** Past car-chat exchanges, newest last, so the next drive can pick up where this one left off. */
class FileChatMemory(context: Context) : dev.aiauto.core.ChatMemory {
    private val list = JsonListFile(File(context.filesDir, "chat_memory.json"), dev.aiauto.core.ChatExchange.serializer())
    private val prefs = context.getSharedPreferences("chat_memory", Context.MODE_PRIVATE)

    override fun recent() = list.flow.value

    /** When the memory was last cleared, here or on another device. Sync keeps older exchanges from coming back. */
    val forgottenMillis: Long get() = prefs.getLong("forgotten", 0L)

    @Synchronized
    override fun remember(exchange: dev.aiauto.core.ChatExchange) =
        list.set((list.flow.value + exchange).takeLast(dev.aiauto.core.ChatMemory.KEEP))

    @Synchronized
    override fun forget() {
        prefs.edit().putLong("forgotten", System.currentTimeMillis()).apply()
        list.set(emptyList())
    }

    /** Replaces everything with [exchanges], for sync, after a clear made on another device at [forgottenAt]. */
    @Synchronized
    fun replaceAll(exchanges: List<dev.aiauto.core.ChatExchange>, forgottenAt: Long) {
        if (forgottenAt > forgottenMillis) prefs.edit().putLong("forgotten", forgottenAt).apply()
        val kept = exchanges.filter { it.timeMillis > forgottenMillis }
        if (kept != list.flow.value) list.set(kept)
    }
}

/** Saved task templates. A new install starts with [TaskTemplate.defaults]; deleting them all keeps the list empty. */
class TemplateStore(context: Context) {
    private val file = File(context.filesDir, "templates.json")
    private val list = JsonListFile(file, TaskTemplate.serializer()).also { if (!file.exists()) it.set(TaskTemplate.defaults) }
    val templates: StateFlow<List<TaskTemplate>> = list.flow

    @Synchronized
    fun add(name: String, prompt: String): TaskTemplate {
        val template = TaskTemplate(java.util.UUID.randomUUID().toString().take(8), name.trim(), prompt.trim())
        list.set(list.flow.value + template)
        return template
    }

    @Synchronized
    fun remove(id: String) = list.set(list.flow.value.filter { it.id != id })
}

/** Non-secret settings, plus the agent setup tied to the current API key. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Volatile private var importing = false

    // Kept in a field: SharedPreferences only holds listeners weakly.
    private val syncedChange = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!importing && key in SYNCED_KEYS) prefs.edit().putLong("settings_changed", System.currentTimeMillis()).apply()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(syncedChange)
    }

    // ---- Sync ------------------------------------------------------------------------------------------------

    /** A random id for this install, so synced tasks know which device runs them. */
    val deviceId: String
        @Synchronized get() = prefs.getString("device_id", null)
            ?: java.util.UUID.randomUUID().toString().take(12).also { prefs.edit().putString("device_id", it).apply() }

    var syncEnabled: Boolean
        get() = prefs.getBoolean("sync_enabled", false)
        set(value) = prefs.edit().putBoolean("sync_enabled", value).apply()

    var syncGistId: String?
        get() = prefs.getString("sync_gist", null)
        set(value) = prefs.edit().apply { if (value == null) remove("sync_gist") else putString("sync_gist", value) }.apply()

    var lastSyncMillis: Long
        get() = prefs.getLong("last_sync", 0L)
        set(value) = prefs.edit().putLong("last_sync", value).apply()

    var lastSyncError: String?
        get() = prefs.getString("last_sync_error", null)
        set(value) = prefs.edit().apply { if (value == null) remove("last_sync_error") else putString("last_sync_error", value) }.apply()

    /** When a setting that syncs last changed on this device, or came in from another one. */
    val settingsChangedMillis: Long get() = prefs.getLong("settings_changed", 0L)

    /** The settings that sync, each as a type letter and its value, like "b:true". */
    fun exportSynced(): Map<String, String> = prefs.all.filterKeys { it in SYNCED_KEYS }.mapNotNull { (key, value) ->
        when (value) {
            is Boolean -> key to "b:$value"
            is Float -> key to "f:$value"
            is Long -> key to "l:$value"
            is Int -> key to "i:$value"
            is String -> key to "s:$value"
            else -> null
        }
    }.toMap()

    /** Applies settings from another device. Settings missing from [values] go back to their defaults. */
    @Synchronized
    fun importSynced(values: Map<String, String>, changedMillis: Long) {
        importing = true
        try {
            val edit = prefs.edit()
            SYNCED_KEYS.filter { it !in values }.forEach { edit.remove(it) }
            values.filterKeys { it in SYNCED_KEYS }.forEach { (key, encoded) ->
                val v = encoded.substringAfter(':')
                when (encoded.substringBefore(':')) {
                    "b" -> edit.putBoolean(key, v.toBoolean())
                    "f" -> v.toFloatOrNull()?.let { edit.putFloat(key, it) }
                    "l" -> v.toLongOrNull()?.let { edit.putLong(key, it) }
                    "i" -> v.toIntOrNull()?.let { edit.putInt(key, it) }
                    "s" -> edit.putString(key, v)
                }
            }
            edit.putLong("settings_changed", changedMillis)
            edit.commit()
        } finally {
            importing = false
        }
    }

    var readAloudInCar: Boolean
        get() = prefs.getBoolean("read_aloud", true)
        set(value) = prefs.edit().putBoolean("read_aloud", value).apply()

    /** Name of a [dev.aiauto.ui.ThemeMode]. */
    var themeMode: String
        get() = prefs.getString("theme_mode", null) ?: "System"
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    /** Name of a [dev.aiauto.ui.Accent]. */
    var accent: String
        get() = prefs.getString("accent", null) ?: "Clay"
        set(value) = prefs.edit().putString("accent", value).apply()

    var quietMode: Boolean
        get() = prefs.getBoolean("quiet_mode", false)
        set(value) = prefs.edit().putBoolean("quiet_mode", value).apply()

    /** Start listening as soon as the car screen opens, so "Hey Google, open Cruise Control" is all it takes. */
    var listenOnOpen: Boolean
        get() = prefs.getBoolean("listen_on_open", true)
        set(value) = prefs.edit().putBoolean("listen_on_open", value).apply()

    /** Keep listening through pauses, in the car and from the phone's mic button, until Stop, instead of sending at the first pause. */
    var listenUntilStop: Boolean
        get() = prefs.getBoolean("listen_until_stop", false)
        set(value) = prefs.edit().putBoolean("listen_until_stop", value).apply()

    /** Read a summary of what happened since the last drive when the phone connects to the car. */
    var briefingOnConnect: Boolean
        get() = prefs.getBoolean("briefing", true)
        set(value) = prefs.edit().putBoolean("briefing", value).apply()

    /** Let the car chat remember past drives. */
    var rememberChats: Boolean
        get() = prefs.getBoolean("remember_chats", true)
        set(value) = prefs.edit().putBoolean("remember_chats", value).apply()

    /** When the phone last disconnected from the car, or 0 if it never has. */
    var lastDriveEndedMillis: Long
        get() = prefs.getLong("last_drive_ended", 0L)
        set(value) = prefs.edit().putLong("last_drive_ended", value).apply()

    /** Daily spending cap in US dollars, or null for none. New installs start at [DEFAULT_DAILY_LIMIT]. */
    var dailyLimitUsd: Double?
        get() = if (prefs.contains("daily_limit")) prefs.getFloat("daily_limit", 0f).toDouble().takeIf { it > 0 } else DEFAULT_DAILY_LIMIT
        set(value) = prefs.edit().putFloat("daily_limit", (value ?: 0.0).toFloat()).apply()

    /** Monthly spending cap in US dollars, or null for none. Applies on top of the daily limit; off by default. */
    var monthlyLimitUsd: Double?
        get() = prefs.getFloat("monthly_limit", 0f).toDouble().takeIf { it > 0 }
        set(value) = prefs.edit().putFloat("monthly_limit", (value ?: 0.0).toFloat()).apply()

    /** Merge a task's pull request once its checks pass. */
    var autoMerge: Boolean
        get() = prefs.getBoolean("auto_merge", true)
        set(value) = prefs.edit().putBoolean("auto_merge", value).apply()

    /** Send failing checks on a task's pull request back to Claude without asking. */
    var autoFixChecks: Boolean
        get() = prefs.getBoolean("auto_fix_checks", true)
        set(value) = prefs.edit().putBoolean("auto_fix_checks", value).apply()

    /** Have Claude resolve merge conflicts on a task's pull request without asking. */
    var autoFixConflicts: Boolean
        get() = prefs.getBoolean("auto_fix_conflicts", true)
        set(value) = prefs.edit().putBoolean("auto_fix_conflicts", value).apply()

    /** When auto-release was switched on, or null when it's off. */
    var autoReleaseSince: Long?
        get() = prefs.getLong("auto_release_since", 0L).takeIf { it > 0 }
        set(value) = prefs.edit().apply {
            if (value == null) remove("auto_release_since") else putLong("auto_release_since", value)
        }.apply()

    /** What Claude has cost today, by the phone's local date. Resets at midnight. */
    @get:Synchronized
    val spentTodayUsd: Double
        get() = if (prefs.getString("spend_day", null) == today()) prefs.getFloat("spend_usd", 0f).toDouble() else 0.0

    /** What Claude has cost this calendar month, by the phone's local date: every day's spend added up. */
    @get:Synchronized
    val spentThisMonthUsd: Double
        get() = when (prefs.getString("spend_month", null)) {
            thisMonth() -> prefs.getFloat("spend_month_usd", 0f).toDouble()
            // Builds before the monthly total only kept today's, so the month starts from that.
            null -> spentTodayUsd
            else -> 0.0
        }

    @Synchronized
    fun addSpend(usd: Double) {
        val day = spentTodayUsd + usd
        val month = spentThisMonthUsd + usd
        prefs.edit()
            .putString("spend_day", today()).putFloat("spend_usd", day.toFloat())
            .putString("spend_month", thisMonth()).putFloat("spend_month_usd", month.toFloat())
            .apply()
    }

    private fun today() = java.time.LocalDate.now().toString()

    private fun thisMonth() = java.time.YearMonth.now().toString()

    /**
     * Builds before this fix read the API's cents as dollars, so saved costs are 100 times too high.
     * Scales today's total and every task's cost down once.
     */
    @Synchronized
    fun fixCostUnits(tasks: FileTaskStore) {
        if (prefs.getInt("cost_units", 1) >= 2) return
        if (prefs.contains("spend_usd")) prefs.edit().putFloat("spend_usd", prefs.getFloat("spend_usd", 0f) / 100f).apply()
        tasks.all().filter { it.costUsd > 0 }.forEach { tasks.put(it.copy(costUsd = it.costUsd / 100)) }
        prefs.edit().putInt("cost_units", 2).apply()
    }

    /** Model, effort, web search and standing rules for coding tasks. */
    var agentProfile: AgentProfile
        get() = prefs.getString("agent_profile", null)
            ?.let { runCatching { json.decodeFromString(AgentProfile.serializer(), it) }.getOrNull() } ?: AgentProfile()
        set(value) = prefs.edit().putString("agent_profile", json.encodeToString(AgentProfile.serializer(), value)).apply()

    /** Model and effort for the spoken chat in the car. */
    var chatProfile: ChatProfile
        get() = ChatProfile(
            model = ModelChoice.of(prefs.getString("chat_model", null)),
            effort = EffortChoice.of(prefs.getString("chat_effort", null), EffortChoice.Low),
        )
        set(value) = prefs.edit().putString("chat_model", value.model.id).putString("chat_effort", value.effort.name).apply()

    /** How fast updates are read aloud; 1 is the engine's normal speed. */
    var speechRate: Float
        get() = prefs.getFloat("speech_rate", 1f)
        set(value) = prefs.edit().putFloat("speech_rate", value).apply()

    /** The text-to-speech voice's name, or null for the phone's default voice. */
    var voiceName: String?
        get() = prefs.getString("voice_name", null)
        set(value) = prefs.edit().apply { if (value == null) remove("voice_name") else putString("voice_name", value) }.apply()

    var agentSetup: AgentSetup?
        get() = prefs.getString("agent_setup", null)?.let { runCatching { json.decodeFromString(AgentSetup.serializer(), it) }.getOrNull() }
        set(value) = prefs.edit().apply {
            if (value == null) remove("agent_setup") else putString("agent_setup", json.encodeToString(AgentSetup.serializer(), value))
        }.apply()

    companion object {
        const val DEFAULT_DAILY_LIMIT = 20.0

        /**
         * Settings that are the same on every device. Left out: the voice (each phone has its own), spending and
         * drive times (counted per device), the agent setup (tied to each device's key), and sync itself.
         */
        val SYNCED_KEYS = setOf(
            "read_aloud", "theme_mode", "accent", "quiet_mode", "daily_limit", "monthly_limit", "auto_merge",
            "auto_fix_checks", "auto_fix_conflicts", "auto_release_since", "agent_profile", "chat_model", "chat_effort", "speech_rate",
            "listen_on_open", "listen_until_stop", "briefing", "remember_chats",
        )
    }
}
