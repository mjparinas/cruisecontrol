package dev.aiauto.car

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.aiauto.core.TaskEngine
import dev.aiauto.graph

/**
 * Tells the task engine when the car is parked, from the speed the car reports. The car counts as parked once it has
 * stood still for [TaskEngine.PARKED_AFTER_MILLIS], and as moving again on the first speed above zero. Without the
 * speed permission, or on a car that doesn't report speed, it always counts as moving.
 */
class ParkedWatcher(private val carContext: CarContext) : DefaultLifecycleObserver {
    private val engine = carContext.graph.engine
    private val handler = Handler(Looper.getMainLooper())
    private val markParked = Runnable { waiting = false; engine.setParked(true) }
    private var waiting = false
    private val listener = OnCarDataAvailableListener<Speed> { speed ->
        val raw = speed.rawSpeedMetersPerSecond
        onSpeed(if (raw.status == CarValue.STATUS_SUCCESS) raw.value else null)
    }
    private var listening = false

    override fun onCreate(owner: LifecycleOwner) {
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_3) return
        if (carContext.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) return listen()
        runCatching {
            carContext.requestPermissions(listOf(PERMISSION)) { granted, _ -> if (PERMISSION in granted) listen() }
        }.onFailure { Log.w("AiAuto", "Couldn't ask for the car speed permission", it) }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        handler.removeCallbacks(markParked)
        if (listening) runCatching { carInfo().removeSpeedListener(listener) }
        engine.setParked(false)
    }

    private fun listen() {
        runCatching { carInfo().addSpeedListener(ContextCompat.getMainExecutor(carContext), listener) }
            .onSuccess { listening = true }
            .onFailure { Log.w("AiAuto", "Couldn't read the car's speed", it) }
    }

    private fun onSpeed(metersPerSecond: Float?) {
        if (TaskEngine.isStandingStill(metersPerSecond)) {
            if (!waiting && !engine.isParked) {
                waiting = true
                handler.postDelayed(markParked, TaskEngine.PARKED_AFTER_MILLIS)
            }
        } else {
            waiting = false
            handler.removeCallbacks(markParked)
            if (engine.isParked) engine.setParked(false)
        }
    }

    private fun carInfo() = carContext.getCarService(CarHardwareManager::class.java).carInfo

    private companion object {
        const val PERMISSION = "com.google.android.gms.permission.CAR_SPEED"
    }
}
