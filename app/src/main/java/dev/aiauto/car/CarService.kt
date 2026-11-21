package dev.aiauto.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.aiauto.core.SteeringWheel
import dev.aiauto.core.WheelAction
import dev.aiauto.graph
import java.security.MessageDigest

/** Entry point Android Auto binds to. */
class AiAutoCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        val builder = HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
        // The sample list only knows the signing keys Google had published when the library shipped. Also
        // trust the Android Auto that is actually installed, as long as it came with the phone or from Play,
        // so a newer Android Auto isn't turned away and the car screen doesn't stay blank.
        KNOWN_HOSTS.forEach { pkg -> trustedDigests(pkg).forEach { builder.addAllowedHost(pkg, it) } }
        return builder.build()
    }

    override fun onCreateSession(): Session = CarSession()

    /** One drive's car screen. With Steering-wheel buttons on, the wheel's media buttons work while it's showing. */
    private class CarSession : Session() {
        private lateinit var mic: MicScreen

        override fun onCreateScreen(intent: Intent): Screen {
            val wheel = WheelButtons(carContext, ::onWheel)
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = wheel.setActive(carContext.graph.settings.wheelButtons)
                override fun onStop(owner: LifecycleOwner) = wheel.setActive(false)
                override fun onDestroy(owner: LifecycleOwner) = wheel.release()
            })
            mic = MicScreen(carContext)
            return mic
        }

        private fun onWheel(action: WheelAction) {
            when (action) {
                WheelAction.Talk -> {
                    carContext.getCarService(ScreenManager::class.java).popToRoot()
                    mic.talk()
                }
                WheelAction.Repeat -> {
                    val speaker = carContext.graph.speaker
                    val again = SteeringWheel.repeatText(speaker.lastSaid)
                    speaker.stop()
                    speaker.speak(again)
                }
            }
        }
    }

    private fun trustedDigests(pkg: String): List<String> = runCatching {
        val pm = packageManager
        val app = pm.getApplicationInfo(pkg, 0)
        val installer = if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
        val isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
        if (!isSystem && installer != PLAY_STORE) return emptyList()
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return emptyList()
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        certs.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }.getOrElse {
        if (it !is PackageManager.NameNotFoundException) Log.w("AiAuto", "Couldn't read $pkg signature", it)
        emptyList()
    }

    private companion object {
        const val PLAY_STORE = "com.android.vending"
        val KNOWN_HOSTS = listOf(
            "com.google.android.projection.gearhead", // Android Auto on the phone
            "com.google.android.apps.automotive.templates.host", // Android Automotive OS
        )
    }
}
