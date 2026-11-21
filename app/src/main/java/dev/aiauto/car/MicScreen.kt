package dev.aiauto.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.aiauto.R
import dev.aiauto.core.Tone
import dev.aiauto.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The car's home screen: one big mic button. Tap it, say anything, and Claude's answer is read aloud
 * and shown here. Once the answer has been read out it listens again, so the driver can reply without
 * tapping; saying nothing ends the conversation. With "Keep listening until Stop" on, pauses don't send:
 * Stop does. Claude can start or answer coding tasks from the conversation too. The task list is one tap
 * away in the corner. A soft tone plays every few seconds while an answer is slow to come back.
 */
class MicScreen(carContext: CarContext) : Screen(carContext) {
    private val g = carContext.graph
    private val voice = VoiceInput(carContext)
    private var state = State.Idle
    private var text = if (g.isConfigured) HELLO else SETUP
    private var heard: String? = null
    private var thinking: Job? = null
    private var untilStop = false

    private enum class State { Idle, Listening, Thinking }

    private var opened = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (opened) return
                opened = true
                greet()
            }
            override fun onStop(owner: LifecycleOwner) {
                if (state == State.Listening) stopListening()
            }
            override fun onDestroy(owner: LifecycleOwner) = voice.cancel()
        })
    }

    /**
     * When the screen first opens: read the welcome-back briefing if there is one, then listen, so opening the app
     * by voice ("Hey Google, open Cruise Control") is enough to start talking.
     */
    private fun greet() {
        if (!g.isConfigured) return
        val listen = g.settings.listenOnOpen
        val briefing = g.takeBriefing()
        if (briefing == null) {
            if (listen) talk()
            return
        }
        show(State.Idle, briefing)
        runCatching { g.speaker.speak(briefing) { if (listen) lifecycleScope.launch { listenForReply(briefing) } } }
    }

    /** Starts listening, as if Talk was tapped. The steering-wheel buttons call this too. */
    fun talk() {
        if (!g.isConfigured) {
            show(State.Idle, SETUP)
            return
        }
        thinking?.cancel()
        g.speaker.stop()
        heard = null
        untilStop = g.settings.listenUntilStop
        show(State.Listening, if (untilStop) "Listening. Tap Stop when you're done." else "Listening…")
        voice.listen(
            onText = { spoken -> answer(spoken) },
            onError = { reason -> show(State.Idle, reason) },
            untilStopped = untilStop,
        )
    }

    /** Starts listening again after [reply] is read out, unless the driver moved on in the meantime. */
    private fun listenForReply(reply: String) {
        if (state == State.Idle && text == reply && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) talk()
    }

    private fun stopListening() {
        voice.cancel()
        show(State.Idle, HELLO)
    }

    private fun answer(spoken: String) {
        heard = spoken
        show(State.Thinking, "Thinking…")
        thinking = lifecycleScope.launch {
            val cues = launch {
                var waited = 0L
                for (at in Tone.thinkingCues()) {
                    delay(at - waited)
                    waited = at
                    g.tones.play(Tone.Thinking)
                }
            }
            val reply = withContext(Dispatchers.IO) {
                runCatching { g.assistant.ask(spoken) }.getOrElse { "Sorry, that didn't go through. Try again." }
            }
            cues.cancel()
            show(State.Idle, reply)
            runCatching { g.speaker.speak(reply) { lifecycleScope.launch { listenForReply(reply) } } }
        }
    }

    private fun show(newState: State, message: String) {
        state = newState
        text = message
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val body = heard?.takeIf { state != State.Listening }?.let { "You: $it\n\n$text" } ?: text
        val main = when (state) {
            State.Listening -> Action.Builder().setTitle("Stop").setOnClickListener {
                if (untilStop) voice.finish() else stopListening()
            }
            State.Thinking -> Action.Builder().setTitle("Ask again").setOnClickListener { talk() }
            State.Idle -> Action.Builder()
                .setTitle("Talk")
                .setIcon(micIcon())
                .setBackgroundColor(CarColor.PRIMARY)
                .setOnClickListener { talk() }
        }.build()
        val tasks = Action.Builder().setTitle("Tasks").setOnClickListener { screenManager.push(TasksScreen(carContext)) }.build()
        val fresh = Action.Builder().setTitle("New chat").setOnClickListener {
            thinking?.cancel()
            voice.cancel()
            g.speaker.stop()
            // reset() waits for an answer that's still on its way, so it must not run on the car's main thread.
            g.scope.launch(Dispatchers.IO) { g.assistant.reset() }
            heard = null
            show(State.Idle, HELLO)
        }.build()
        return MessageTemplate.Builder(body)
            .setTitle("Claude")
            .setHeaderAction(Action.APP_ICON)
            .setIcon(micIcon())
            .addAction(main)
            .setActionStrip(ActionStrip.Builder().addAction(fresh).addAction(tasks).build())
            .build()
    }

    private fun micIcon(): CarIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_mic)).build()

    private companion object {
        const val HELLO = "Tap Talk and ask Claude anything."
        const val SETUP = "Open Cruise Control on your phone and add your API key first."
    }
}
