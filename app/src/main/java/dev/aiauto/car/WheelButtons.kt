package dev.aiauto.car

import android.content.Context
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import dev.aiauto.core.SteeringWheel
import dev.aiauto.core.WheelAction
import dev.aiauto.core.WheelButton

/**
 * The experimental steering-wheel buttons. While active, a media session takes the wheel's play, pause, next and
 * previous buttons and hands what they mean to [onAction] on the main thread. A media session can make Android
 * Auto treat the app as a media app, with its own audio screen, so it's only active when the setting is on.
 */
class WheelButtons(context: Context, private val onAction: (WheelAction) -> Unit) {
    private val session = MediaSession(context, "CruiseControlWheel")

    init {
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() = press(WheelButton.PlayPause)
                override fun onPause() = press(WheelButton.PlayPause)
                override fun onSkipToNext() = press(WheelButton.Next)
                override fun onSkipToPrevious() = press(WheelButton.Previous)
            },
            Handler(Looper.getMainLooper()),
        )
        // Paused rather than playing, so the app never claims to be playing music.
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
    }

    private fun press(button: WheelButton) = onAction(SteeringWheel.actionFor(button))

    fun setActive(active: Boolean) {
        if (session.isActive != active) session.isActive = active
    }

    fun release() = session.release()
}
