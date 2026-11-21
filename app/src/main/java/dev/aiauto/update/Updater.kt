package dev.aiauto.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.aiauto.core.AvailableUpdate
import dev.aiauto.core.Failures
import dev.aiauto.core.UpdateSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Checks GitHub for a newer release whenever the app comes to the foreground, and installs it
 * on request: downloads the APK, then hands it to Android's package installer.
 */
class Updater(private val context: Context, private val scope: CoroutineScope) {
    sealed interface State {
        data object Idle : State
        data class Available(val update: AvailableUpdate) : State
        data class Downloading(val update: AvailableUpdate) : State
        data class Failed(val update: AvailableUpdate, val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val http = OkHttpClient()
    private val source = UpdateSource(http = http)
    private var lastFetchMillis = 0L
    private var cached: AvailableUpdate? = null

    val currentVersion: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    /** Call when the app starts or returns to the foreground. Asks GitHub at most every few minutes. */
    fun check() {
        if (_state.value is State.Downloading) return
        val now = System.currentTimeMillis()
        if (now - lastFetchMillis < FETCH_GAP_MILLIS) {
            cached?.let { _state.value = State.Available(it) }
            return
        }
        lastFetchMillis = now
        scope.launch(Dispatchers.IO) {
            // A failed check is silent; the next foreground tries again.
            runCatching { source.newerThan(currentVersion) }.onSuccess { update ->
                cached = update
                if (update != null && _state.value !is State.Downloading) _state.value = State.Available(update)
            }
        }
    }

    /**
     * Asks GitHub right away, for the Check for updates button in Settings. A newer release shows the
     * usual update prompt. Returns a short line to show under the button.
     */
    suspend fun checkNow(): String {
        if (_state.value is State.Downloading) return "An update is already downloading."
        return withContext(Dispatchers.IO) {
            runCatching { source.newerThan(currentVersion) }.fold(
                { update ->
                    lastFetchMillis = System.currentTimeMillis()
                    cached = update
                    if (update != null && _state.value !is State.Downloading) _state.value = State.Available(update)
                    update?.let { "${it.tag} is available." } ?: "You're on the latest version."
                },
                { "Couldn't check for updates. ${Failures.describe(it)}" },
            )
        }
    }

    /** Hides the prompt until the app next comes to the foreground. */
    fun later() {
        if (_state.value !is State.Downloading) _state.value = State.Idle
    }

    fun install(update: AvailableUpdate) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            // Android asks once per app. The prompt shows again when the user comes back from Settings.
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        _state.value = State.Downloading(update)
        scope.launch(Dispatchers.IO) {
            runCatching { download(update) }
                .onSuccess { apk ->
                    _state.value = State.Idle
                    openInstaller(apk)
                }
                .onFailure { _state.value = State.Failed(update, Failures.describe(it)) }
        }
    }

    private fun download(update: AvailableUpdate): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only keep the APK being installed
        val apk = File(dir, "aiauto-${update.tag}.apk")
        http.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("The download failed (HTTP ${response.code}).")
            apk.outputStream().use { out -> response.body.byteStream().copyTo(out) }
        }
        if (update.apkSize > 0 && apk.length() != update.apkSize) {
            apk.delete()
            throw IllegalStateException("The download was cut off. Try again.")
        }
        if (!signedLikeThisApp(apk)) {
            apk.delete()
            throw IllegalStateException("The download isn't signed with this app's key, so it wasn't installed.")
        }
        return apk
    }

    /**
     * True unless the APK is plainly a different app or signed with a different key. Android's installer checks the
     * key too, but stopping here keeps a tampered download from ever reaching the install prompt. When the signature
     * can't be read on this phone, the installer's own check decides.
     */
    private fun signedLikeThisApp(apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return@runCatching true
        if (archive.packageName != context.packageName) return@runCatching false
        val theirs = signers(archive)
        val ours = signers(pm.getPackageInfo(context.packageName, flags))
        theirs.isEmpty() || ours.isEmpty() || theirs.any { it in ours }
    }.getOrDefault(true)

    private fun signers(info: PackageInfo): Set<String> {
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION") info.signatures
        }
        return certs.orEmpty().map { it.toCharsString() }.toSet()
    }

    private fun openInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private companion object {
        const val FETCH_GAP_MILLIS = 5 * 60_000L
    }
}
