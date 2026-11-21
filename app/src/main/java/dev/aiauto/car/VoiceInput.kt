package dev.aiauto.car

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import dev.aiauto.core.Dictation
import dev.aiauto.graph

/**
 * Speech to text started from the car screen, or from the phone's prompt box with "Keep listening until Stop" on.
 * In the car it uses the car's microphone when the host supports it (car API 5 and Android 13+), and the phone's
 * microphone otherwise. With untilStopped, pauses don't end it: listening restarts after each one and the words
 * are joined until [finish] is called. Errors before then, which the recognizer often gives while restarting or after
 * a stretch of silence, restart it too instead of ending or sending. On the phone, the caller asks for the microphone permission first.
 * Spoken updates are held from the start of listening until it ends, and read afterwards.
 */
class VoiceInput(private val context: Context) {
    private val carContext = context as? CarContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()
    private var recognizer: SpeechRecognizer? = null
    private var carAudioFd: ParcelFileDescriptor? = null
    @Volatile private var recording = false
    private var dictation: Dictation? = null
    private var holdingSpeech = false
    private val main = Handler(Looper.getMainLooper())

    fun listen(onText: (String) -> Unit, onError: (String) -> Unit, untilStopped: Boolean = false) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition isn't available on this phone.")
            return
        }
        val car = carContext
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            start(untilStopped, onText, onError)
        } else if (car == null) {
            onError(MIC_PERMISSION)
        } else {
            runCatching {
                car.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ ->
                    if (Manifest.permission.RECORD_AUDIO in granted) start(untilStopped, onText, onError)
                    else onError(MIC_PERMISSION)
                }
            }.onFailure { onError("Open Cruise Control on your phone once to allow the microphone.") }
        }
    }

    /** Stops listening and sends what was heard, once the recognizer has handed over the last words. */
    fun finish() {
        val d = dictation ?: return
        d.stop()
        recording = false
        recognizer?.stopListening()
    }

    fun cancel() {
        dictation = null
        release()
        holdSpeech(false)
    }

    /** Holds spoken updates for a whole go, across restarts after pauses and the switch to the phone's microphone. */
    private fun holdSpeech(on: Boolean) {
        if (holdingSpeech == on) return
        holdingSpeech = on
        if (on) context.graph.speaker.holdForMicrophone() else context.graph.speaker.releaseMicrophone()
    }

    private fun release() {
        recording = false
        recognizer?.destroy()
        recognizer = null
        carAudioFd?.close()
        carAudioFd = null
        audio.abandonAudioFocusRequest(focus)
    }

    private fun start(untilStopped: Boolean, onText: (String) -> Unit, onError: (String) -> Unit) {
        cancel()
        dictation = Dictation(untilStopped)
        holdSpeech(true)
        val useCarMic = Build.VERSION.SDK_INT >= 33 && (carContext?.carAppApiLevel ?: 0) >= CarAppApiLevels.LEVEL_5
        recognize(useCarMic, onText, onError)
    }

    private fun recognize(useCarMic: Boolean, onText: (String) -> Unit, onError: (String) -> Unit) {
        audio.requestAudioFocus(focus)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        if (useCarMic && Build.VERSION.SDK_INT >= 33) {
            // The car's microphone isn't always available (host quirks, another app recording). Use the phone's.
            if (runCatching { addCarMicrophone(intent) }.isFailure) {
                release()
                recognize(false, onText, onError)
                return
            }
        }

        var heardSpeech = false
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrElse {
            release()
            next(dictation?.broke(), useCarMic, onText, onError)
            return
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onBeginningOfSpeech() { heardSpeech = true }
            override fun onEndOfSpeech() { recording = false }

            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                release()
                next(dictation?.pause(text), useCarMic, onText, onError)
            }

            override fun onError(error: Int) {
                release()
                val nothingSaid = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                val d = dictation ?: return
                when {
                    // Some recognizers can't take audio from the car; fall back to the phone's microphone.
                    useCarMic && !heardSpeech && !nothingSaid -> recognize(false, onText, onError)
                    nothingSaid -> next(d.pause(null), useCarMic, onText, onError)
                    else -> next(d.broke(), useCarMic, onText, onError, failure = "Voice input stopped. Try again.")
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching { r.startListening(intent) }.onFailure {
            release()
            if (useCarMic) recognize(false, onText, onError) else next(dictation?.broke(), false, onText, onError)
        }
    }

    /** Listens again after a pause, or ends this go and hands over the words or what went wrong. */
    private fun next(
        step: Dictation.Next?,
        useCarMic: Boolean,
        onText: (String) -> Unit,
        onError: (String) -> Unit,
        failure: String = "Voice input isn't working right now. Reply by message instead.",
    ) {
        val d = dictation ?: return
        if (step == Dictation.Next.Listen) {
            // Starting again straight away often finds the old recognizer still busy, so wait a moment.
            main.postDelayed({
                when {
                    dictation !== d -> Unit
                    d.stopped -> next(d.pause(null), useCarMic, onText, onError)
                    else -> recognize(useCarMic, onText, onError)
                }
            }, RESTART_DELAY_MS)
            return
        }
        dictation = null
        holdSpeech(false)
        when (step) {
            Dictation.Next.Send -> onText(d.text)
            Dictation.Next.NothingHeard -> onError("I didn't catch that.")
            else -> onError(failure)
        }
    }

    /** Streams the car microphone into the recognizer through a pipe. */
    @RequiresApi(33)
    private fun addCarMicrophone(intent: Intent) {
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        carAudioFd = readSide
        val record = CarAudioRecord.create(checkNotNull(carContext))
        recording = true
        record.startRecording()
        Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                    val buffer = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
                    val deadline = SystemClock.elapsedRealtime() + MAX_LISTEN_MS
                    while (recording && SystemClock.elapsedRealtime() < deadline) {
                        val n = record.read(buffer, 0, buffer.size)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
            }
            record.stopRecording()
        }.start()
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE)
    }

    companion object {
        private const val MAX_LISTEN_MS = 20_000L
        private const val RESTART_DELAY_MS = 300L
        const val MIC_PERMISSION = "Allow microphone access to talk to Claude."
    }
}
