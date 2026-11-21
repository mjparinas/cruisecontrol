package dev.aiauto.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import dev.aiauto.core.Tone

/**
 * Plays the listening and thinking [Tone]s with the assistant audio attributes the microphone uses, so Android Auto
 * routes them to the car speakers and music ducks for them as it does for speech.
 */
class TonePlayer(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private val samples = Tone.entries.associateWith { it.samples() }
    private val main = Handler(Looper.getMainLooper())

    fun play(tone: Tone) {
        // A tone is only a hint, so a phone that can't play it just stays quiet.
        runCatching {
            val pcm = samples.getValue(tone)
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Tone.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            audio.requestAudioFocus(focus)
            track.play()
            main.postDelayed({
                track.release()
                audio.abandonAudioFocusRequest(focus)
            }, tone.durationMs + RELEASE_AFTER_MS)
        }
    }

    private companion object {
        /** Extra time before the track is freed, so the end of the tone isn't cut off. */
        const val RELEASE_AFTER_MS = 100L
    }
}
