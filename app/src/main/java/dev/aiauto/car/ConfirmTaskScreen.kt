package dev.aiauto.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import dev.aiauto.core.RepoRef
import dev.aiauto.core.SpokenStatus
import dev.aiauto.core.VoiceCommand
import dev.aiauto.graph

/** Reads a new request back and waits for a separate yes before starting it. */
class ConfirmTaskScreen(
    carContext: CarContext,
    private val repo: RepoRef,
    private val request: String,
    private val onConfirm: () -> Unit,
) : Screen(carContext) {
    private val g = carContext.graph
    private val voice = VoiceInput(carContext)
    private val prompt = "I heard: ${SpokenStatus.clean(request)} in ${repo.name}. Say yes to start, or no to cancel."
    private var message = prompt
    private var started = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (started) return
                started = true
                g.speaker.speak(prompt) { listen() }
            }

            override fun onStop(owner: LifecycleOwner) = voice.cancel()
            override fun onDestroy(owner: LifecycleOwner) = voice.cancel()
        })
    }

    private fun listen() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        message = if (g.settings.listenUntilStop) "$prompt Tap Stop after saying yes or no." else prompt
        invalidate()
        voice.listen(
            onText = { spoken ->
                when (VoiceCommand.confirmation(spoken)) {
                    VoiceCommand.Yes -> confirm()
                    VoiceCommand.No -> screenManager.pop()
                    else -> {
                        message = "I didn't catch yes or no. Say yes to start, or no to cancel."
                        invalidate()
                        g.speaker.speak(message) { listen() }
                    }
                }
            },
            onError = { reason ->
                message = "$reason Say yes to start, or no to cancel."
                invalidate()
            },
            untilStopped = g.settings.listenUntilStop,
        )
    }

    private fun confirm() {
        screenManager.pop()
        onConfirm()
    }

    override fun onGetTemplate(): Template {
        val template = MessageTemplate.Builder(message)
            .setHeader(carHeader("Confirm task"))
        if (g.settings.listenUntilStop) {
            template.addAction(Action.Builder().setTitle("Stop listening").setOnClickListener(voice::finish).build())
        } else {
            template.addAction(
                Action.Builder()
                    .setTitle("Yes, start")
                    .setBackgroundColor(CarColor.PRIMARY)
                    .setOnClickListener(::confirm)
                    .build(),
            )
        }
        return template
            .addAction(Action.Builder().setTitle("Cancel").setOnClickListener { screenManager.pop() }.build())
            .build()
    }
}
