package dev.aiauto.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.aiauto.graph

/**
 * Listens for a spoken request right on the car screen. On success it closes itself and hands the
 * text to [onText]. If voice fails, the driver can try again or fall back to the messaging reply. With
 * "Keep listening until Stop" on, pauses don't end it and a Stop button sends what was said.
 */
class ListenScreen(
    carContext: CarContext,
    private val title: String,
    private val fallback: () -> Unit,
    private val onText: (String) -> Unit,
) : Screen(carContext) {
    private val voice = VoiceInput(carContext)
    private val untilStop = carContext.graph.settings.listenUntilStop
    private var message = listening()
    private var failed = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = listen()
            override fun onDestroy(owner: LifecycleOwner) = voice.cancel()
        })
    }

    private fun listen() {
        failed = false
        message = listening()
        invalidate()
        carContext.graph.speaker.stop()
        voice.listen(
            onText = { text ->
                screenManager.pop()
                onText(text)
            },
            onError = { reason ->
                failed = true
                message = reason
                invalidate()
            },
            untilStopped = untilStop,
        )
    }

    private fun listening() = if (untilStop) "$LISTENING Tap Stop when you're done." else LISTENING

    override fun onGetTemplate(): Template {
        val template = MessageTemplate.Builder(message)
            .setTitle(title)
            .setHeaderAction(Action.BACK)
        if (failed) {
            template.addAction(Action.Builder().setTitle("Try again").setOnClickListener { listen() }.build())
            template.addAction(
                Action.Builder().setTitle("Reply by message").setOnClickListener {
                    screenManager.pop()
                    fallback()
                }.build(),
            )
        } else if (untilStop) {
            template.addAction(Action.Builder().setTitle("Stop").setOnClickListener { voice.finish() }.build())
        }
        return template.build()
    }

    private companion object {
        const val LISTENING = "Listening. Say your request."
    }
}
