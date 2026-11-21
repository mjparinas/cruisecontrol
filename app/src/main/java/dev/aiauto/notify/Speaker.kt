package dev.aiauto.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import dev.aiauto.core.SpeechHold
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads updates aloud through the phone's text-to-speech, which Android Auto routes to the
 * car speakers. Only speaks while the phone is connected to a car. Music is ducked while it talks,
 * and anything said before the speech engine is ready is queued instead of dropped. While the microphone is listening,
 * updates are held and read once it stops, so the car doesn't talk over the driver.
 */
/** A text-to-speech voice you can pick in Settings. */
data class VoiceOption(val name: String, val label: String)

class Speaker(context: Context, private val settings: dev.aiauto.data.Settings) {
    @Volatile var inCar = false
        private set
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private val queued = AtomicInteger(0)
    private val utteranceIds = AtomicInteger(0)
    private val pending = mutableListOf<Pair<String, (() -> Unit)?>>()
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val hold = SpeechHold<Pair<String, (() -> Unit)?>>(MAX_PENDING)
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }

    init {
        CarConnection(context.applicationContext).type.observeForever(Observer { type ->
            inCar = type == CarConnection.CONNECTION_TYPE_PROJECTION || type == CarConnection.CONNECTION_TYPE_NATIVE
        })
    }

    @Synchronized
    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale.getDefault()
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
        })
        ready = true
        pending.forEach { (text, onDone) -> say(text, onDone) }
        pending.clear()
    }

    /** Queues [text]; [onDone] runs on a background thread once it has been read out, unless [stop] cancels it. */
    @Synchronized
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) return
        if (hold.offer(text to onDone)) return
        if (ready) say(text, onDone)
        else if (pending.size < MAX_PENDING) pending.add(text to onDone)
    }

    /** Holds new updates while the microphone listens. Call [releaseMicrophone] once for every call. */
    @Synchronized
    fun holdForMicrophone() = hold.hold()

    /** Ends one microphone hold; once none are left, reads out what was held. */
    @Synchronized
    fun releaseMicrophone() = hold.release().forEach { (text, onDone) -> speak(text, onDone) }

    @Synchronized
    fun stop() {
        pending.clear()
        hold.clear()
        callbacks.clear()
        if (ready) tts.stop()
        queued.set(0)
        audio.abandonAudioFocusRequest(focus)
    }

    /**
     * Voices for the phone's language that the engine has installed, best first. Empty until the engine is ready.
     * Voices that need a network connection are marked, since they can drop out on a bad signal.
     */
    @Synchronized
    fun voices(): List<VoiceOption> {
        if (!ready) return emptyList()
        val language = Locale.getDefault().language
        val all = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.locale.language == language && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.locale.displayName }, { it.name }))
        val perLocale = HashMap<String, Int>()
        return all.map { voice ->
            val place = voice.locale.displayName
            val n = perLocale.merge(place, 1, Int::plus)!!
            VoiceOption(voice.name, "$place, voice $n" + if (voice.isNetworkConnectionRequired) " (needs internet)" else "")
        }
    }

    /** Applies the speed and voice from Settings before each utterance, so changes take effect right away. */
    private fun applyVoice() {
        runCatching { tts.setSpeechRate(settings.speechRate) }
        val wanted = settings.voiceName
        val voice = wanted?.let { name -> runCatching { tts.voices?.firstOrNull { it.name == name } }.getOrNull() }
        runCatching { if (voice != null) tts.voice = voice else if (wanted == null) tts.voice = tts.defaultVoice }
    }

    private fun say(text: String, onDone: (() -> Unit)?) {
        applyVoice()
        if (queued.getAndIncrement() == 0) audio.requestAudioFocus(focus)
        val id = "u" + utteranceIds.incrementAndGet()
        onDone?.let { callbacks[id] = it }
        val result = runCatching { tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) }
        // If the engine refused it, no onDone will come, so give the music its volume back now.
        if (result.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.ERROR) finished(id)
    }

    private fun finished(utteranceId: String?) {
        if (queued.decrementAndGet() <= 0) {
            queued.set(0)
            audio.abandonAudioFocusRequest(focus)
        }
        utteranceId?.let { callbacks.remove(it)?.invoke() }
    }

    private companion object {
        const val MAX_PENDING = 5
    }
}
