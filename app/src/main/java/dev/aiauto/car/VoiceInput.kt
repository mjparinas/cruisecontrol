package dev.aiauto.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
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
import dev.aiauto.graph

/**
 * Speech to text started from the car screen. Uses the car's microphone when the host supports it
 * (car API 5 and Android 13+), and the phone's microphone otherwise. Spoken updates are held while it listens.
 */
class VoiceInput(private val carContext: CarContext) {
    private val audio = carContext.getSystemService(AudioManager::class.java)
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
    private var holdingSpeech = false

    fun listen(onText: (String) -> Unit, onError: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(carContext)) {
            onError("Speech recognition isn't available on this phone.")
            return
        }
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            start(onText, onError)
        } else {
            runCatching {
                carContext.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ ->
                    if (Manifest.permission.RECORD_AUDIO in granted) start(onText, onError)
                    else onError("Allow microphone access to talk to Claude.")
                }
            }.onFailure { onError("Open Cruise Control on your phone once to allow the microphone.") }
        }
    }

    fun cancel() {
        stopRecognizer()
        audio.abandonAudioFocusRequest(focus)
        if (holdingSpeech) {
            holdingSpeech = false
            carContext.graph.speaker.releaseMicrophone()
        }
    }

    /** Stops listening but keeps audio focus and the speech hold, for switching to the phone's microphone. */
    private fun stopRecognizer() {
        recording = false
        recognizer?.destroy()
        recognizer = null
        carAudioFd?.close()
        carAudioFd = null
    }

    private fun start(onText: (String) -> Unit, onError: (String) -> Unit) {
        cancel()
        val useCarMic = Build.VERSION.SDK_INT >= 33 && carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_5
        recognize(useCarMic, onText, onError)
    }

    private fun recognize(useCarMic: Boolean, onText: (String) -> Unit, onError: (String) -> Unit) {
        audio.requestAudioFocus(focus)
        if (!holdingSpeech) {
            holdingSpeech = true
            carContext.graph.speaker.holdForMicrophone()
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        if (useCarMic && Build.VERSION.SDK_INT >= 33) {
            // The car's microphone isn't always available (host quirks, another app recording). Use the phone's.
            if (runCatching { addCarMicrophone(intent) }.isFailure) {
                stopRecognizer()
                recognize(false, onText, onError)
                return
            }
        }

        var heardSpeech = false
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(carContext) }.getOrElse {
            cancel()
            onError("Voice input isn't working right now. Reply by message instead.")
            return
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onBeginningOfSpeech() { heardSpeech = true }
            override fun onEndOfSpeech() { recording = false }

            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                cancel()
                if (text.isNullOrEmpty()) onError("I didn't catch that.") else onText(text)
            }

            override fun onError(error: Int) {
                val nothingSaid = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                // Some recognizers can't take audio from the car; fall back to the phone's microphone.
                if (useCarMic && !heardSpeech && !nothingSaid) {
                    stopRecognizer()
                    recognize(false, onText, onError)
                    return
                }
                cancel()
                onError(if (nothingSaid) "I didn't catch that." else "Voice input stopped. Try again.")
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching { r.startListening(intent) }.onFailure {
            if (useCarMic) {
                stopRecognizer()
                recognize(false, onText, onError)
            } else {
                cancel()
                onError("Voice input isn't working right now. Reply by message instead.")
            }
        }
    }

    /** Streams the car microphone into the recognizer through a pipe. */
    @RequiresApi(33)
    private fun addCarMicrophone(intent: Intent) {
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        carAudioFd = readSide
        val record = CarAudioRecord.create(carContext)
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

    private companion object {
        const val MAX_LISTEN_MS = 20_000L
    }
}
